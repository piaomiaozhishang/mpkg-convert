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

    /**
     * 流式提取：把视频 mip（MP4）直接解压/拷贝到 [out]，全程不把大文件读进内存。
     *
     * 头部解析与 [extract] 一致；载荷区分两种情况：
     *  - 未压缩：分块拷贝；
     *  - LZ4：用 [Lz4BlockStream] 边解边写（内存占用恒定 ~1 MiB）。
     *
     * @return 写出的字节数（= 解压后大小）；flag 表明是视频但载荷不是已知 MP4 时返回 -1。
     */
    fun extractVideoTo(
        src: SeekableData,
        lz4: Lz4BlockDecompressor?,
        out: java.io.OutputStream,
        mobileLayout: Boolean = false
    ): Long {
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
        if (imageCount <= 0) return -1

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
        if (version == 4 && (mobileLayout || !containerSaysVideo)) version = 3

        if (!headerSaysVideo && !containerSaysVideo) return -1

        val mipmapCount = r.readInt32()
        if (mipmapCount <= 0) return -1

        // --- mip0 header（载荷不读进内存）---
        var isLz4 = false
        var decompressedSize = 0
        var byteCount = 0
        when (version) {
            1 -> {
                r.readInt32() // width
                r.readInt32() // height
                byteCount = r.readInt32()
            }
            2, 3 -> {
                r.readInt32() // width
                r.readInt32() // height
                isLz4 = r.readInt32() == 1
                decompressedSize = r.readInt32()
                byteCount = r.readInt32()
            }
            4 -> {
                require(r.readInt32() == 1) { "Unexpected TEX v4 param1" }
                require(r.readInt32() == 2) { "Unexpected TEX v4 param2" }
                r.readNString() // condition json
                require(r.readInt32() == 1) { "Unexpected TEX v4 param3" }
                r.readInt32() // width
                r.readInt32() // height
                isLz4 = r.readInt32() == 1
                decompressedSize = r.readInt32()
                byteCount = r.readInt32()
            }
            else -> throw FormatException("Unsupported TEX container version $version")
        }
        if (byteCount <= 0) return -1
        val payloadStart = r.position

        // --- MP4 品牌检查（ftyp…）---
        val head: ByteArray = if (!isLz4) {
            src.readAt(payloadStart, minOf(12, byteCount))
        } else {
            // 只解前 64 字节用于检查（stopAt 提前停止），不污染输出流
            Lz4BlockStream.decompressToByteArray(
                src.regionStream(payloadStart, byteCount.toLong()),
                decompressedSize.toLong(),
                stopAt = 64L
            )
        }
        if (head.size >= 12) {
            val brand = String(head, 4, 8, Charsets.US_ASCII).lowercase()
            if (brand !in MP4_BRANDS) return -1
        }

        // --- 流式写出 ---
        return if (isLz4) {
            val expected = if (decompressedSize > 0) decompressedSize.toLong() else byteCount.toLong()
            Lz4BlockStream.decompress(src.regionStream(payloadStart, byteCount.toLong()), expected, out)
        } else {
            src.copyRangeTo(out, payloadStart, byteCount.toLong())
        }
    }
}
