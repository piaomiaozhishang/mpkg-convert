package com.doubao.repkg.core

/** Result of decoding a ".tex" texture: either an embedded video or a picture. */
sealed class TexAsset {
    class Video(val bytes: ByteArray) : TexAsset()

    /**
     * A static picture.
     * @param rgba            decoded RGBA8888 pixels (when the texture was raw/DXT);
     *                        null when the payload was an already-encoded image file
     * @param encodedBytes    original bytes when the payload was an encoded image file
     *                        (PNG/JPEG/GIF/...); null when [rgba] is used
     * @param extension       suggested file extension, e.g. "png" / "jpg"
     */
    class Image(
        val width: Int,
        val height: Int,
        val rgba: ByteArray?,
        val encodedBytes: ByteArray?,
        val extension: String,
        val texFormat: Int,
        val imageFormat: Int,
        /** Compressed block stream (e.g. ETC2_EAC) needing platform GPU decoding. */
        val compressedBytes: ByteArray? = null
    ) : TexAsset()
}

/** Lightweight header view (dimensions + formats), decoded without reading pixels. */
data class TexPeek(
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val texFormat: Int,
    val imageFormat: Int
)

/**
 * Decodes a Wallpaper Engine ".tex" texture into either its embedded MP4
 * (video wallpaper) or its first image (static wallpaper).
 *
 * Format resolution follows RePKG: the container's FreeImageFormat wins;
 * when unknown, the TEX header format (raw / DXT) is used.
 */
object TexAssetDecoder {

    private const val FLAG_IS_VIDEO_TEXTURE = 32
    private const val TEX_FORMAT_ETC2_EAC = 5

    private val MP4_BRANDS = setOf("ftypisom", "ftypmsnv", "ftypmp42")

    /** FreeImageFormat value -> suggested file extension (from RePKG). */
    private val FREE_IMAGE_EXTENSIONS = mapOf(
        0 to "bmp", 1 to "ico", 2 to "jpg", 3 to "jng", 4 to "koa", 5 to "lbm",
        6 to "mng", 7 to "pbm", 8 to "pbm", 9 to "pcd", 10 to "pcx", 11 to "pgm",
        12 to "pgm", 13 to "png", 14 to "ppm", 15 to "ppm", 16 to "ras", 17 to "tga",
        18 to "tif", 19 to "wbmp", 20 to "psd", 21 to "cut", 22 to "xbm", 23 to "xpm",
        24 to "dds", 25 to "gif", 26 to "hdr", 27 to "g3", 28 to "sgi", 29 to "exr",
        30 to "j2k", 31 to "jp2", 32 to "pfm", 33 to "pict", 34 to "raw", 35 to "mp4"
    )

    /** Reads only the header / mip0 dimensions (fast, no pixel payload). */
    fun peek(texBytes: ByteArray, mobileLayout: Boolean = false): TexPeek? {
        val p = parse(texBytes, wantBytes = false, lz4 = null, mobileLayout = mobileLayout) ?: return null
        return TexPeek(
            isVideo = p.isVideo,
            width = p.mipWidth,
            height = p.mipHeight,
            texFormat = p.texFormat,
            imageFormat = p.imageFormat
        )
    }

    fun decode(
        texBytes: ByteArray,
        lz4: Lz4BlockDecompressor? = null,
        mobileLayout: Boolean = false
    ): TexAsset? {
        val p = parse(texBytes, wantBytes = true, lz4 = lz4, mobileLayout = mobileLayout) ?: return null
        val mipBytes = p.mipBytes!!

        // --- Video path: payload must be an MP4 ---
        if (p.isVideo) {
            if (mipBytes.size < 12) return null
            val brand = String(mipBytes, 4, 8, Charsets.US_ASCII).lowercase()
            if (brand in MP4_BRANDS) return TexAsset.Video(mipBytes)
            return null
        }

        // --- Image path ---
        // 1) Encoded image payload: the bytes ARE the image file.
        if (p.imageFormat in FREE_IMAGE_EXTENSIONS) {
            val ext = FREE_IMAGE_EXTENSIONS.getValue(p.imageFormat)
            if (ext == "raw") return null
            return TexAsset.Image(
                width = p.mipWidth,
                height = p.mipHeight,
                rgba = null,
                encodedBytes = mipBytes,
                extension = ext,
                texFormat = p.texFormat,
                imageFormat = p.imageFormat
            )
        }

        // 2) ETC2_EAC (Format=5): the standard mobile static-texture format.
        //    Core (pure JVM) cannot decode it; hand the block stream to the
        //    platform GPU (OpenGL ES 3.0) for decoding.
        if (p.texFormat == TEX_FORMAT_ETC2_EAC) {
            return TexAsset.Image(
                width = p.mipWidth,
                height = p.mipHeight,
                rgba = null,
                encodedBytes = null,
                extension = "png",
                texFormat = p.texFormat,
                imageFormat = p.imageFormat,
                compressedBytes = mipBytes
            )
        }

        // 3) Raw / DXT-compressed pixels: decode to RGBA8888.
        val decoded = decodePixels(p.texFormat, p.mipWidth, p.mipHeight, mipBytes) ?: return null

        val outWidth = if (p.imageWidth > 0 && p.imageWidth <= p.mipWidth) p.imageWidth else p.mipWidth
        val outHeight = if (p.imageHeight > 0 && p.imageHeight <= p.mipHeight) p.imageHeight else p.mipHeight
        val rgba = if (outWidth == p.mipWidth && outHeight == p.mipHeight) {
            decoded
        } else {
            cropRgba(decoded, p.mipWidth, outWidth, outHeight)
        }

        return TexAsset.Image(
            width = outWidth,
            height = outHeight,
            rgba = rgba,
            encodedBytes = null,
            extension = "png",
            texFormat = p.texFormat,
            imageFormat = p.imageFormat
        )
    }

    // --- Internal parsed structure ---

    private class Parsed(
        val texFormat: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val imageFormat: Int,
        val isVideo: Boolean,
        val mipWidth: Int,
        val mipHeight: Int,
        val mipBytes: ByteArray?
    )

    private fun parse(
        texBytes: ByteArray,
        wantBytes: Boolean,
        lz4: Lz4BlockDecompressor?,
        mobileLayout: Boolean
    ): Parsed? {
        val src = ByteArraySeekableData(texBytes)
        val r = LeReader(src)

        val magic1 = r.readNString(maxLength = 16)
        if (magic1 != "TEXV0005") {
            throw FormatException("Not a TEX file (magic1='$magic1')")
        }
        val magic2 = r.readNString(maxLength = 16)
        if (magic2 != "TEXI0001") {
            throw FormatException("Unsupported TEX image magic '$magic2'")
        }

        // --- Header ---
        val texFormat = r.readInt32()
        val flags = r.readInt32()
        r.readInt32() // texture width
        r.readInt32() // texture height
        val imageWidth = r.readInt32()
        val imageHeight = r.readInt32()
        r.readUInt32() // unkInt0

        val headerSaysVideo = (flags and FLAG_IS_VIDEO_TEXTURE) == FLAG_IS_VIDEO_TEXTURE

        // --- Image container ---
        val containerMagic = r.readNString(maxLength = 16)
        val imageCount = r.readInt32()
        if (imageCount <= 0) return null

        var imageFormat = -1
        var containerSaysVideo = false
        when (containerMagic) {
            "TEXB0001", "TEXB0002" -> { /* no extra fields */ }
            "TEXB0003" -> imageFormat = r.readInt32()
            "TEXB0004" -> {
                imageFormat = r.readInt32()
                containerSaysVideo = r.readInt32() == 1
            }
            else -> throw FormatException("Unsupported TEX container '$containerMagic'")
        }

        var version = containerMagic.substring(4).toIntOrNull()
            ?: throw FormatException("Bad TEX container version '$containerMagic'")
        if (version == 4) {
            // Mobile (PKGM) packages store TEXB0004 with the compact v3 mipmap
            // layout (no param block), regardless of the video flag. Desktop
            // (PKGV) video textures keep the v4 layout.
            if (mobileLayout || !containerSaysVideo) version = 3
        }

        // --- First image / first mipmap ---
        val mipmapCount = r.readInt32()
        if (mipmapCount <= 0) return null

        var mipWidth = 0
        var mipHeight = 0
        var isLz4 = false
        var decompressedSize = 0
        var byteCount = 0
        when (version) {
            1 -> {
                mipWidth = r.readInt32()
                mipHeight = r.readInt32()
                byteCount = r.readInt32()
            }
            2, 3 -> {
                mipWidth = r.readInt32()
                mipHeight = r.readInt32()
                isLz4 = r.readInt32() == 1
                decompressedSize = r.readInt32()
                byteCount = r.readInt32()
            }
            4 -> {
                require(r.readInt32() == 1) { "Unexpected TEX v4 param1" }
                require(r.readInt32() == 2) { "Unexpected TEX v4 param2" }
                r.readNString()
                require(r.readInt32() == 1) { "Unexpected TEX v4 param3" }
                mipWidth = r.readInt32()
                mipHeight = r.readInt32()
                isLz4 = r.readInt32() == 1
                decompressedSize = r.readInt32()
                byteCount = r.readInt32()
            }
            else -> throw FormatException("Unsupported TEX container version $version")
        }

        val isVideo = headerSaysVideo || containerSaysVideo || imageFormat == 35

        if (!wantBytes) {
            return Parsed(
                texFormat, imageWidth, imageHeight, imageFormat, isVideo,
                mipWidth, mipHeight, null
            )
        }

        var mipBytes = r.readBytes(byteCount)
        if (isLz4) {
            val d = lz4 ?: throw FormatException("LZ4-compressed texture but no decompressor provided")
            mipBytes = d.decompress(mipBytes, decompressedSize)
        }

        return Parsed(
            texFormat, imageWidth, imageHeight, imageFormat, isVideo,
            mipWidth, mipHeight, mipBytes
        )
    }

    private fun decodePixels(texFormat: Int, width: Int, height: Int, bytes: ByteArray): ByteArray? {
        if (width <= 0 || height <= 0) return null
        return when (texFormat) {
            0 -> bytes
            4 -> Dxt.decompress(width, height, bytes, Dxt.DXT5)
            6 -> Dxt.decompress(width, height, bytes, Dxt.DXT3)
            7 -> Dxt.decompress(width, height, bytes, Dxt.DXT1)
            8 -> {
                val out = ByteArray(width * height * 4)
                for (i in 0 until width * height) {
                    out[4 * i] = bytes[2 * i]
                    out[4 * i + 1] = bytes[2 * i + 1]
                    out[4 * i + 2] = 0
                    out[4 * i + 3] = (-1).toByte()
                }
                out
            }
            9 -> {
                val out = ByteArray(width * height * 4)
                for (i in 0 until width * height) {
                    val v = bytes[i]
                    out[4 * i] = v
                    out[4 * i + 1] = v
                    out[4 * i + 2] = v
                    out[4 * i + 3] = (-1).toByte()
                }
                out
            }
            else -> null
        }
    }

    private fun cropRgba(src: ByteArray, srcWidth: Int, outWidth: Int, outHeight: Int): ByteArray {
        val out = ByteArray(outWidth * outHeight * 4)
        for (y in 0 until outHeight) {
            System.arraycopy(src, y * srcWidth * 4, out, y * outWidth * 4, outWidth * 4)
        }
        return out
    }
}
