package com.doubao.repkg.core

/** Decompresses a raw (non-framed) LZ4 block. Implemented with lz4-java on Android. */
fun interface Lz4BlockDecompressor {
    fun decompress(source: ByteArray, knownDecompressedLength: Int): ByteArray
}

/**
 * Extracts an MP4 payload from a Wallpaper Engine ".tex" video texture.
 *
 * A video texture stores the complete MP4 file as the first mipmap of its first
 * image (optionally LZ4 compressed). Non-video textures return null.
 */
object TexVideoExtractor {

    private const val FLAG_IS_VIDEO_TEXTURE = 32

    private val MP4_BRANDS = setOf("ftypisom", "ftypmsnv", "ftypmp42")

    fun extract(
        texBytes: ByteArray,
        lz4: Lz4BlockDecompressor? = null
    ): ByteArray? {
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
        r.readInt32() // tex format
        val flags = r.readInt32()
        r.readInt32() // texture width
        r.readInt32() // texture height
        r.readInt32() // image width
        r.readInt32() // image height
        r.readUInt32() // unkInt0

        val headerSaysVideo = (flags and FLAG_IS_VIDEO_TEXTURE) == FLAG_IS_VIDEO_TEXTURE

        // --- Image container ---
        val containerMagic = r.readNString(maxLength = 16)
        val imageCount = r.readInt32()
        if (imageCount <= 0) return null

        var containerSaysVideo = false
        when (containerMagic) {
            "TEXB0001", "TEXB0002" -> { /* no extra fields */ }
            "TEXB0003" -> r.readInt32() // free image format
            "TEXB0004" -> {
                r.readInt32() // free image format
                containerSaysVideo = r.readInt32() == 1
            }
            else -> throw FormatException("Unsupported TEX container '$containerMagic'")
        }

        var version = containerMagic.substring(4).toIntOrNull()
            ?: throw FormatException("Bad TEX container version '$containerMagic'")
        // v4 with non-mp4 content is parsed like v3 (matches original behaviour).
        if (version == 4 && !containerSaysVideo) version = 3

        if (!headerSaysVideo && !containerSaysVideo) return null

        // --- First image ---
        val mipmapCount = r.readInt32()
        if (mipmapCount <= 0) return null

        var mipBytes: ByteArray
        when (version) {
            1 -> {
                r.readInt32() // width
                r.readInt32() // height
                val byteCount = r.readInt32()
                mipBytes = r.readBytes(byteCount)
            }
            2, 3 -> {
                r.readInt32() // width
                r.readInt32() // height
                val isLz4 = r.readInt32() == 1
                val decompressedSize = r.readInt32()
                val byteCount = r.readInt32()
                mipBytes = r.readBytes(byteCount)
                if (isLz4) {
                    val d = lz4 ?: throw FormatException("LZ4-compressed video but no decompressor provided")
                    mipBytes = d.decompress(mipBytes, decompressedSize)
                }
            }
            4 -> {
                require(r.readInt32() == 1) { "Unexpected TEX v4 param1" }
                require(r.readInt32() == 2) { "Unexpected TEX v4 param2" }
                r.readNString() // condition json
                require(r.readInt32() == 1) { "Unexpected TEX v4 param3" }
                r.readInt32() // width
                r.readInt32() // height
                val isLz4 = r.readInt32() == 1
                val decompressedSize = r.readInt32()
                val byteCount = r.readInt32()
                mipBytes = r.readBytes(byteCount)
                if (isLz4) {
                    val d = lz4 ?: throw FormatException("LZ4-compressed video but no decompressor provided")
                    mipBytes = d.decompress(mipBytes, decompressedSize)
                }
            }
            else -> throw FormatException("Unsupported TEX container version $version")
        }

        if (mipBytes.size < 12) return null
        val brand = String(mipBytes, 4, 8, Charsets.US_ASCII).lowercase()
        if (brand !in MP4_BRANDS) {
            // Looks like a video texture flag but payload is not a known MP4.
            return null
        }
        return mipBytes
    }
}
