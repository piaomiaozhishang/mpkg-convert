package com.doubao.repkg

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.doubao.repkg.core.AssetKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Persists extracted assets (MP4 videos and pictures).
 *
 *  - Custom folder chosen by the user (SAF) : written via DocumentFile.
 *  - Otherwise Android 10+                  : MediaStore, scoped storage.
 *  - Android 9-                             : direct file in Movies / Pictures.
 *
 * Output folder is [FOLDER] ("mpkg转换") under Movies / Pictures.
 *
 * 大文件（视频可达数 GB）一律流式写入（1 MiB 缓冲），绝不让整文件进内存。
 */
object AssetSaver {

    /** Output folder under Movies / Pictures. Also shown in Settings, so keep it public. */
    const val FOLDER = "mpkg转换"

    private const val COPY_BUFFER = 1 shl 20   // 1 MiB

    sealed interface Result {
        data class Saved(val uri: Uri, val path: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** 数据来源：内存字节，或磁盘上的临时文件（大视频）。 */
    private sealed class Payload {
        class Bytes(val data: ByteArray) : Payload()
        class FromFile(val file: File) : Payload()

        val sizeBytes: Long
            get() = when (this) {
                is Bytes -> data.size.toLong()
                is FromFile -> file.length()
            }

        fun writeTo(out: OutputStream) {
            when (this) {
                is Bytes -> out.write(data)
                is FromFile -> file.inputStream().use { input ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** Saves in-memory bytes to the custom folder when one is set, otherwise the default location. */
    fun save(context: Context, kind: AssetKind, fileName: String, bytes: ByteArray): Result =
        save(context, kind, fileName, Payload.Bytes(bytes))

    /** Streams a file (e.g. a large extracted MP4) to the target location without loading it. */
    fun saveFromFile(context: Context, kind: AssetKind, fileName: String, source: File): Result {
        if (!source.exists()) return Result.Failed("临时文件已丢失，请重新解析")
        return save(context, kind, fileName, Payload.FromFile(source))
    }

    private fun save(context: Context, kind: AssetKind, fileName: String, payload: Payload): Result {
        val treeUri = ExportSettings.getTreeUri(context)
        if (treeUri != null) {
            return saveToTree(context, treeUri, kind, fileName, payload)
        }
        val relativeDir = when (kind) {
            AssetKind.VIDEO -> "${Environment.DIRECTORY_MOVIES}/$FOLDER"
            AssetKind.IMAGE -> "${Environment.DIRECTORY_PICTURES}/$FOLDER"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, kind, fileName, payload, relativeDir)
        } else {
            saveLegacy(kind, fileName, payload)
        }
    }

    /** Writes directly into the user-picked SAF folder (no extra sub-folder). */
    private fun saveToTree(
        context: Context,
        treeUri: Uri,
        kind: AssetKind,
        fileName: String,
        payload: Payload
    ): Result {
        return try {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.Failed("无法访问所选文件夹")
            if (!tree.canWrite()) return Result.Failed("所选文件夹不可写")

            val mime = mimeFor(kind, fileName)
            val existing = tree.listFiles().firstOrNull { it.name == fileName && it.isFile }
            val doc = existing ?: tree.createFile(mime, fileName)
                ?: return Result.Failed("无法在所选文件夹创建文件")

            context.contentResolver.openOutputStream(doc.uri, "w")?.use { payload.writeTo(it) }
                ?: return Result.Failed("无法写入所选文件夹")

            val folderName = tree.name ?: "所选文件夹"
            Result.Saved(doc.uri, "$folderName/$fileName")
        } catch (e: Exception) {
            Result.Failed(e.message ?: "写入自定义文件夹失败")
        }
    }

    /** Encodes RGBA8888 pixels into a PNG byte array (using Android Bitmap). */
    fun encodePng(pixelsRgba: ByteArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixelsRgba))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Encodes an existing Bitmap to lossless PNG bytes and recycles it. */
    fun encodeBitmapPng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun saveViaMediaStore(
        context: Context,
        kind: AssetKind,
        fileName: String,
        payload: Payload,
        relativeDir: String
    ): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(kind, fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = when (kind) {
            AssetKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            AssetKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = resolver.insert(collection, values)
            ?: return Result.Failed("无法创建媒体文件")

        return try {
            resolver.openOutputStream(uri, "w")?.use { payload.writeTo(it) }
                ?: return Result.Failed("无法写入媒体文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Saved(uri, "$relativeDir/$fileName")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Result.Failed(e.message ?: "写入失败")
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(kind: AssetKind, fileName: String, payload: Payload): Result {
        return try {
            val base = when (kind) {
                AssetKind.VIDEO -> Environment.DIRECTORY_MOVIES
                AssetKind.IMAGE -> Environment.DIRECTORY_PICTURES
            }
            val dir = File(Environment.getExternalStoragePublicDirectory(base), FOLDER)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.outputStream().use { payload.writeTo(it) }
            Result.Saved(Uri.fromFile(file), file.absolutePath)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "写入失败")
        }
    }

    fun mimeFor(kind: AssetKind, fileName: String): String {
        if (kind == AssetKind.VIDEO) return "video/mp4"
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }
}
