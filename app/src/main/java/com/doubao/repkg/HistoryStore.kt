package com.doubao.repkg

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.doubao.repkg.core.AssetKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One entry in the conversion history. */
data class HistoryRecord(
    val id: Long,
    val timeMs: Long,
    val kind: AssetKind,
    val fileName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val uriString: String,
    val location: String,
    /** Absolute path of the local thumbnail, or null. */
    val thumbPath: String?
) {
    val uri: Uri get() = Uri.parse(uriString)
}

/**
 * Stores conversion history as a JSON file in internal storage, with small
 * thumbnails kept alongside. Deliberately avoids Room to keep the build simple.
 */
object HistoryStore {

    private const val DIR = "history"
    private const val FILE = "history.json"
    private const val THUMB_DIR = "thumbnails"

    /** Decode target before the final 360px scale — avoids decoding a 4K source. */
    private const val THUMB_SOURCE_EDGE = 720

    fun getAll(context: Context): List<HistoryRecord> {
        val raw = file(context).takeIf { it.exists() }?.readText() ?: return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("records") ?: JSONArray()
            (0 until arr.length()).map { i -> toRecord(arr.getJSONObject(i)) }
                .sortedByDescending { it.timeMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, record: HistoryRecord) {
        val list = getAll(context).toMutableList()
        list.add(0, record)
        persist(context, list)
    }

    fun delete(context: Context, id: Long) {
        val list = getAll(context).toMutableList()
        val target = list.firstOrNull { it.id == id }
        target?.thumbPath?.let { File(it).delete() }
        persist(context, list.filterNot { it.id == id })
    }

    fun clear(context: Context) {
        thumbDir(context).listFiles()?.forEach { it.delete() }
        file(context).delete()
    }

    // --- Thumbnail helpers ---

    /** Builds (and saves) a thumbnail from an encoded image's bytes. */
    fun makeImageThumb(context: Context, id: Long, imageBytes: ByteArray): String? {
        return try {
            // Decode downsampled: the source may be a 4K PNG and we only need 360px.
            val bmp = ImageLoader.decodeBytes(imageBytes, THUMB_SOURCE_EDGE) ?: return null
            val saved = saveThumb(context, id, bmp)
            bmp.recycle()
            saved
        } catch (e: Exception) {
            null
        }
    }

    /** Builds (and saves) a video cover thumbnail from the just-saved video URI. */
    fun makeVideoThumb(context: Context, id: Long, uri: Uri): String? {
        return try {
            val frame = VideoThumbnail.fromUri(context, uri) ?: return null
            val saved = saveThumb(context, id, frame)
            frame.recycle()
            saved
        } catch (e: Exception) {
            null
        }
    }

    /** Builds a thumbnail from raw RGBA pixels. */
    fun makePixelThumb(
        context: Context,
        id: Long,
        pixels: ByteArray,
        width: Int,
        height: Int
    ): String? {
        return try {
            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
            val saved = saveThumb(context, id, full)
            full.recycle()
            saved
        } catch (e: Exception) {
            null
        }
    }

    private fun saveThumb(context: Context, id: Long, source: Bitmap): String? {
        return try {
            val maxEdge = 360
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(source.width, source.height))
            val tw = (source.width * scale).toInt().coerceAtLeast(1)
            val th = (source.height * scale).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(source, tw, th, true)
            val out = File(thumbDir(context), "$id.jpg")
            out.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (thumb != source) thumb.recycle()
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // --- Persistence ---

    private fun persist(context: Context, records: List<HistoryRecord>) {
        val arr = JSONArray()
        records.sortedByDescending { it.timeMs }.forEach { arr.put(toJson(it)) }
        val root = JSONObject().put("records", arr)
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(root.toString())
    }

    private fun toJson(r: HistoryRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("timeMs", r.timeMs)
        put("kind", if (r.kind == AssetKind.VIDEO) "video" else "image")
        put("fileName", r.fileName)
        put("sizeBytes", r.sizeBytes)
        put("width", r.width)
        put("height", r.height)
        put("uriString", r.uriString)
        put("location", r.location)
        put("thumbPath", r.thumbPath ?: JSONObject.NULL)
    }

    private fun toRecord(o: JSONObject): HistoryRecord = HistoryRecord(
        id = o.getLong("id"),
        timeMs = o.getLong("timeMs"),
        kind = if (o.getString("kind") == "video") AssetKind.VIDEO else AssetKind.IMAGE,
        fileName = o.getString("fileName"),
        sizeBytes = o.getLong("sizeBytes"),
        width = o.optInt("width", 0),
        height = o.optInt("height", 0),
        uriString = o.getString("uriString"),
        location = o.optString("location", ""),
        thumbPath = if (o.isNull("thumbPath")) null else o.optString("thumbPath")
    )

    private fun file(context: Context): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, FILE)
    }

    private fun thumbDir(context: Context): File =
        File(context.filesDir, "$DIR/$THUMB_DIR").apply { mkdirs() }
}
