package com.doubao.repkg.core

/**
 * S3TC (DXT1 / DXT3 / DXT5) block decompression.
 *
 * Ported from the MIT-licensed implementation used by RePKG
 * (DXT.cs, originally Xalcon @ mmowned.com, based on LibSquish),
 * adapted to Kotlin.
 */
object Dxt {

    const val DXT1 = 1
    const val DXT3 = 2
    const val DXT5 = 3

    /**
     * Decompresses a DXT1/3/5 image into tightly packed RGBA8888 bytes.
     * Width/height need not be multiples of 4 (edge blocks are clipped).
     */
    fun decompress(width: Int, height: Int, data: ByteArray, flag: Int): ByteArray {
        val rgba = ByteArray(width * height * 4)
        var srcPos = 0
        val bytesPerBlock = if (flag == DXT1) 8 else 16
        val target = ByteArray(16 * 4)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (data.size == srcPos) break

                // decompress one 4x4 block into target[]
                val colorBlockIndex = srcPos + if (flag == DXT3 || flag == DXT5) 8 else 0
                decompressColor(target, data, colorBlockIndex, flag == DXT1)
                if (flag == DXT3) decompressAlphaDxt3(target, data, srcPos)
                else if (flag == DXT5) decompressAlphaDxt5(target, data, srcPos)

                // write out pixels inside the image bounds
                var targetPos = 0
                for (py in 0 until 4) {
                    for (px in 0 until 4) {
                        val sx = x + px
                        val sy = y + py
                        if (sx < width && sy < height) {
                            val dst = 4 * (width * sy + sx)
                            rgba[dst] = target[targetPos]
                            rgba[dst + 1] = target[targetPos + 1]
                            rgba[dst + 2] = target[targetPos + 2]
                            rgba[dst + 3] = target[targetPos + 3]
                        }
                        targetPos += 4
                    }
                }
                srcPos += bytesPerBlock
                x += 4
            }
            y += 4
        }
        return rgba
    }

    private fun decompressAlphaDxt3(rgba: ByteArray, block: ByteArray, blockIndex: Int) {
        for (i in 0 until 8) {
            val quant = block[blockIndex + i].toInt() and 0xFF
            val lo = quant and 0x0F
            val hi = (quant and 0xF0) ushr 4
            rgba[8 * i + 3] = (lo or (lo shl 4)).toByte()
            rgba[8 * i + 7] = (hi or (hi shl 4)).toByte()
        }
    }

    private fun decompressAlphaDxt5(rgba: ByteArray, block: ByteArray, blockIndex: Int) {
        val alpha0 = block[blockIndex].toInt() and 0xFF
        val alpha1 = block[blockIndex + 1].toInt() and 0xFF

        val codes = ByteArray(8)
        codes[0] = alpha0.toByte()
        codes[1] = alpha1.toByte()
        if (alpha0 <= alpha1) {
            // 5-alpha codebook (6 = transparent, 7 = opaque)
            for (i in 1 until 5) codes[1 + i] = (((5 - i) * alpha0 + i * alpha1) / 5).toByte()
            codes[6] = 0
            codes[7] = (-1).toByte()
        } else {
            // 7-alpha codebook
            for (i in 1 until 7) codes[i + 1] = (((7 - i) * alpha0 + i * alpha1) / 7).toByte()
        }

        // 16 3-bit indices packed into 6 bytes (2 groups of 24 bits)
        val indices = ByteArray(16)
        var src = 2
        var idx = 0
        for (i in 0 until 2) {
            var value = 0
            for (j in 0 until 3) {
                value = value or ((block[blockIndex + src].toInt() and 0xFF) shl (8 * j))
                src++
            }
            for (j in 0 until 8) {
                indices[idx++] = ((value ushr (3 * j)) and 0x07).toByte()
            }
        }

        for (i in 0 until 16) rgba[4 * i + 3] = codes[indices[i].toInt() and 0xFF]
    }

    private fun decompressColor(rgba: ByteArray, block: ByteArray, blockIndex: Int, isDxt1: Boolean) {
        val codes = ByteArray(16)
        val a = unpack565(block, blockIndex, 0, codes, 0)
        val b = unpack565(block, blockIndex, 2, codes, 4)

        for (i in 0 until 3) {
            val c = codes[i].toInt() and 0xFF
            val d = codes[4 + i].toInt() and 0xFF
            if (isDxt1 && a <= b) {
                codes[8 + i] = ((c + d) / 2).toByte()
                codes[12 + i] = 0
            } else {
                codes[8 + i] = ((2 * c + d) / 3).toByte()
                codes[12 + i] = ((c + 2 * d) / 3).toByte()
            }
        }
        codes[8 + 3] = (-1).toByte()
        codes[12 + 3] = if (isDxt1 && a <= b) 0 else (-1).toByte()

        // 16 2-bit color indices: 4 bytes, each holding 4 pixels
        for (i in 0 until 4) {
            val packed = block[blockIndex + 4 + i].toInt() and 0xFF
            val base = i * 4
            for (j in 0 until 4) {
                val index = (packed ushr (2 * j)) and 0x3
                val offset = 4 * index
                val dst = 4 * (base + j)
                rgba[dst] = codes[offset]
                rgba[dst + 1] = codes[offset + 1]
                rgba[dst + 2] = codes[offset + 2]
                rgba[dst + 3] = codes[offset + 3]
            }
        }
    }

    private fun unpack565(
        block: ByteArray,
        blockIndex: Int,
        packedOffset: Int,
        colour: ByteArray,
        colourOffset: Int
    ): Int {
        val value = (block[blockIndex + packedOffset].toInt() and 0xFF) or
                ((block[blockIndex + 1 + packedOffset].toInt() and 0xFF) shl 8)

        val red = (value ushr 11) and 0x1F
        val green = (value ushr 5) and 0x3F
        val blue = value and 0x1F

        colour[colourOffset] = ((red shl 3) or (red ushr 2)).toByte()
        colour[colourOffset + 1] = ((green shl 2) or (green ushr 4)).toByte()
        colour[colourOffset + 2] = ((blue shl 3) or (blue ushr 2)).toByte()
        colour[colourOffset + 3] = (-1).toByte()

        return value
    }
}
