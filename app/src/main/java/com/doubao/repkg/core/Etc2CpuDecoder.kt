package com.doubao.repkg.core

/*
 * Etc2CpuDecoder.kt -- pure-Kotlin ETC2_RGBA8 (tex Format=5) decoder, no GPU / no native code.
 *
 * Fallback for devices where the OpenGL ES 3.0 offscreen path (Etc2Decoder) is
 * unavailable (some emulators, old drivers) or throws. Verified against the
 * reference oracle: 60/60 block vectors in `app/src/test/resources/testdata`
 * (see Etc2CpuDecoderTest), and byte-identical output to the GPU path.
 *
 * A 4x4 texel block is 16 bytes:
 *     bytes [0..7]  = 64-bit EAC alpha block
 *     bytes [8..15] = 64-bit ETC2 RGB block  (ETC1 + ETC2 T/H/planar)
 * Both halves are BIG-endian 64-bit words.
 *
 * NOTE: uses shared scratch arrays -> NOT re-entrant; decode on a single thread.
 */

object Etc2CpuDecoder {

    private val ETC_MOD = arrayOf(
        intArrayOf(2, 8, -2, -8),
        intArrayOf(5, 17, -5, -17),
        intArrayOf(9, 29, -9, -29),
        intArrayOf(13, 42, -13, -42),
        intArrayOf(18, 60, -18, -60),
        intArrayOf(24, 80, -24, -80),
        intArrayOf(33, 106, -33, -106),
        intArrayOf(47, 183, -47, -183),
    )

    private val ETC_DIST = intArrayOf(3, 6, 11, 16, 23, 32, 41, 64)

    private val EAC_MOD = arrayOf(
        intArrayOf(-3, -6, -9, -15, 2, 5, 8, 14),
        intArrayOf(-3, -7, -10, -13, 2, 6, 9, 12),
        intArrayOf(-2, -5, -8, -13, 1, 4, 7, 12),
        intArrayOf(-2, -4, -6, -13, 1, 3, 5, 12),
        intArrayOf(-3, -6, -8, -12, 2, 5, 7, 11),
        intArrayOf(-3, -7, -9, -11, 2, 6, 8, 10),
        intArrayOf(-4, -7, -8, -11, 3, 6, 7, 10),
        intArrayOf(-3, -5, -8, -11, 2, 4, 7, 10),
        intArrayOf(-2, -6, -8, -10, 1, 5, 7, 9),
        intArrayOf(-2, -5, -8, -10, 1, 4, 7, 9),
        intArrayOf(-2, -4, -8, -10, 1, 3, 7, 9),
        intArrayOf(-2, -5, -7, -10, 1, 4, 6, 9),
        intArrayOf(-3, -4, -7, -10, 2, 3, 6, 9),
        intArrayOf(-1, -2, -3, -10, 0, 1, 2, 9),
        intArrayOf(-4, -6, -8, -9, 3, 5, 7, 8),
        intArrayOf(-3, -5, -7, -9, 2, 4, 6, 8),
    )

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
    private fun signExtend(v: Int, bits: Int): Int { val m = 1 shl (bits - 1); return (v xor m) - m }

    /** Extract a `mask`-wide field starting at `shift` (LSB numbering after the big-endian read). */
    private fun bits(v: Long, shift: Int, mask: Int): Int = ((v ushr shift).toInt()) and mask

    private val rgbScratch = IntArray(48)
    private val alphaScratch = IntArray(16)

    private fun be64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFFL)
        return v
    }

    // ------------------------------------------------------------------ RGB half

    private fun decodeRgb(blk: Long, out: IntArray) {
        val flip = bits(blk, 32, 1)
        val diff = bits(blk, 33, 1)
        var r0: Int; var g0: Int; var b0: Int
        var r1: Int; var g1: Int; var b1: Int

        if (diff == 0) {
            r0 = bits(blk, 60, 0xF); r1 = bits(blk, 56, 0xF)
            g0 = bits(blk, 52, 0xF); g1 = bits(blk, 48, 0xF)
            b0 = bits(blk, 44, 0xF); b1 = bits(blk, 40, 0xF)
            r0 = (r0 shl 4) or r0; g0 = (g0 shl 4) or g0; b0 = (b0 shl 4) or b0
            r1 = (r1 shl 4) or r1; g1 = (g1 shl 4) or g1; b1 = (b1 shl 4) or b1
            paintLegacy(blk, flip, r0, g0, b0, r1, g1, b1, out)
            return
        }

        r0 = bits(blk, 59, 0x1F); g0 = bits(blk, 51, 0x1F); b0 = bits(blk, 43, 0x1F)
        r1 = r0 + signExtend(bits(blk, 56, 0x7), 3)
        g1 = g0 + signExtend(bits(blk, 48, 0x7), 3)
        b1 = b0 + signExtend(bits(blk, 40, 0x7), 3)

        when {
            r1 < 0 || r1 > 31 -> decodeModeT(blk, out)
            g1 < 0 || g1 > 31 -> decodeModeH(blk, out)
            b1 < 0 || b1 > 31 -> decodeModePlanar(blk, out)
            else -> {
                r0 = (r0 shl 3) or (r0 ushr 2); g0 = (g0 shl 3) or (g0 ushr 2); b0 = (b0 shl 3) or (b0 ushr 2)
                r1 = (r1 shl 3) or (r1 ushr 2); g1 = (g1 shl 3) or (g1 ushr 2); b1 = (b1 shl 3) or (b1 ushr 2)
                paintLegacy(blk, flip, r0, g0, b0, r1, g1, b1, out)
            }
        }
    }

    private fun paintLegacy(
        blk: Long, flip: Int,
        r0: Int, g0: Int, b0: Int, r1: Int, g1: Int, b1: Int, out: IntArray
    ) {
        val mod0 = ETC_MOD[bits(blk, 37, 0x7)]
        val mod1 = ETC_MOD[bits(blk, 34, 0x7)]
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val sub = if (flip == 0) col shr 1 else row shr 1
                val m = row + (col shl 2)
                val idx = (bits(blk, m + 16, 1) shl 1) or bits(blk, m, 1)
                val d: Int
                val base = row * 12 + col * 3
                if (sub == 0) {
                    d = mod0[idx]
                    out[base] = clamp255(r0 + d); out[base + 1] = clamp255(g0 + d); out[base + 2] = clamp255(b0 + d)
                } else {
                    d = mod1[idx]
                    out[base] = clamp255(r1 + d); out[base + 1] = clamp255(g1 + d); out[base + 2] = clamp255(b1 + d)
                }
            }
        }
    }

    private fun paintIndexed(blk: Long, palette: IntArray, out: IntArray) {
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val m = row + (col shl 2)
                val idx = (bits(blk, m + 16, 1) shl 1) or bits(blk, m, 1)
                val base = row * 12 + col * 3
                out[base] = palette[idx * 3]
                out[base + 1] = palette[idx * 3 + 1]
                out[base + 2] = palette[idx * 3 + 2]
            }
        }
    }

    private fun decodeModeT(blk: Long, out: IntArray) {
        var r0 = (bits(blk, 59, 0x3) shl 2) or bits(blk, 56, 0x3)
        var g0 = bits(blk, 52, 0xF)
        var b0 = bits(blk, 48, 0xF)
        var r1 = bits(blk, 44, 0xF)
        var g1 = bits(blk, 40, 0xF)
        var b1 = bits(blk, 36, 0xF)
        val dist = ETC_DIST[(bits(blk, 34, 0x3) shl 1) or bits(blk, 32, 0x1)]

        r0 = (r0 shl 4) or r0; g0 = (g0 shl 4) or g0; b0 = (b0 shl 4) or b0
        r1 = (r1 shl 4) or r1; g1 = (g1 shl 4) or g1; b1 = (b1 shl 4) or b1

        val palette = intArrayOf(
            r0, g0, b0,
            clamp255(r1 + dist), clamp255(g1 + dist), clamp255(b1 + dist),
            r1, g1, b1,
            clamp255(r1 - dist), clamp255(g1 - dist), clamp255(b1 - dist),
        )
        paintIndexed(blk, palette, out)
    }

    private fun decodeModeH(blk: Long, out: IntArray) {
        val r0 = bits(blk, 59, 0xF)
        val g0 = (bits(blk, 56, 0x7) shl 1) or bits(blk, 52, 0x1)
        val b0 = (bits(blk, 51, 0x1) shl 3) or bits(blk, 47, 0x7)
        val r1 = bits(blk, 43, 0xF)
        val g1 = bits(blk, 39, 0xF)
        val b1 = bits(blk, 35, 0xF)
        val da = bits(blk, 34, 0x1)
        val db = bits(blk, 32, 0x1)

        val e0r = (r0 shl 4) or r0; val e0g = (g0 shl 4) or g0; val e0b = (b0 shl 4) or b0
        val e1r = (r1 shl 4) or r1; val e1g = (g1 shl 4) or g1; val e1b = (b1 shl 4) or b1

        val cmp = if (((e0r shl 16) or (e0g shl 8) or e0b) >= ((e1r shl 16) or (e1g shl 8) or e1b)) 1 else 0
        val dist = ETC_DIST[(da shl 2) or (db shl 1) or cmp]

        val palette = intArrayOf(
            clamp255(e0r + dist), clamp255(e0g + dist), clamp255(e0b + dist),
            clamp255(e0r - dist), clamp255(e0g - dist), clamp255(e0b - dist),
            clamp255(e1r + dist), clamp255(e1g + dist), clamp255(e1b + dist),
            clamp255(e1r - dist), clamp255(e1g - dist), clamp255(e1b - dist),
        )
        paintIndexed(blk, palette, out)
    }

    private fun decodeModePlanar(blk: Long, out: IntArray) {
        var ro = bits(blk, 57, 0x3F)
        val go = (bits(blk, 56, 0x1) shl 6) or bits(blk, 49, 0x3F)
        val bo = (bits(blk, 48, 0x1) shl 5) or (bits(blk, 43, 0x3) shl 3) or bits(blk, 39, 0x7)
        var rh = (bits(blk, 34, 0x1F) shl 1) or bits(blk, 32, 0x1)
        var gh = bits(blk, 25, 0x7F)
        var bh = bits(blk, 19, 0x3F)
        var rv = bits(blk, 13, 0x3F)
        var gv = bits(blk, 6, 0x7F)
        var bv = bits(blk, 0, 0x3F)

        ro = (ro shl 2) or (ro ushr 4)
        rh = (rh shl 2) or (rh ushr 4)
        rv = (rv shl 2) or (rv ushr 4)
        val goe = (go shl 1) or (go ushr 6)
        gh = (gh shl 1) or (gh ushr 6)
        gv = (gv shl 1) or (gv ushr 6)
        val boe = (bo shl 2) or (bo ushr 4)
        bh = (bh shl 2) or (bh ushr 4)
        bv = (bv shl 2) or (bv ushr 4)

        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val base = row * 12 + col * 3
                out[base] = clamp255((col * (rh - ro) + row * (rv - ro) + (ro shl 2) + 2) shr 2)
                out[base + 1] = clamp255((col * (gh - goe) + row * (gv - goe) + (goe shl 2) + 2) shr 2)
                out[base + 2] = clamp255((col * (bh - boe) + row * (bv - boe) + (boe shl 2) + 2) shr 2)
            }
        }
    }

    // ------------------------------------------------------------------ EAC alpha

    private fun decodeAlpha(blk: Long, out: IntArray) {
        val base = bits(blk, 56, 0xFF)
        val mult = bits(blk, 52, 0xF)
        val mods = EAC_MOD[bits(blk, 48, 0xF)]
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val k = 15 - ((col shl 2) + row)
                val idx = bits(blk, k * 3, 0x7)
                out[row * 4 + col] = clamp255(base + mods[idx] * mult)
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /**
     * Decode one 16-byte ETC2_RGBA8 block into [outRgba] at [outOff], as 64 bytes
     * in row-major order (index = row*4+col, 4 bytes each: R,G,B,A).
     */
    fun decodeBlock(src: ByteArray, srcOff: Int, outRgba: ByteArray, outOff: Int) {
        val rgbBlk = be64(src, srcOff + 8)
        val aBlk = be64(src, srcOff)
        val rgb = rgbScratch
        val alpha = alphaScratch
        decodeRgb(rgbBlk, rgb)
        decodeAlpha(aBlk, alpha)
        var o = outOff
        for (i in 0 until 16) {
            outRgba[o++] = rgb[i * 3].toByte()
            outRgba[o++] = rgb[i * 3 + 1].toByte()
            outRgba[o++] = rgb[i * 3 + 2].toByte()
            outRgba[o++] = alpha[i].toByte()
        }
    }

    /**
     * Decode a whole ETC2_RGBA8 image into a flat ARGB int array (Bitmap-friendly).
     * Works for any width/height; the last partial block row/column is clipped.
     */
    fun decodeArgb(data: ByteArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "size must be positive" }
        val out = IntArray(width * height)
        val bw = (width + 3) shr 2
        val bh = (height + 3) shr 2
        val blk = ByteArray(64)
        var p = 0
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                // tolerate a truncated stream for the very last block
                if (p + 16 > data.size) return out
                decodeBlock(data, p, blk, 0)
                p += 16
                for (row in 0 until 4) {
                    val y = by * 4 + row
                    if (y >= height) break
                    var o = y * width + bx * 4
                    var b = row * 16
                    for (c in 0 until 4) {
                        val x = bx * 4 + c
                        if (x < width) {
                            val r = blk[b].toInt() and 0xFF
                            val g = blk[b + 1].toInt() and 0xFF
                            val bl = blk[b + 2].toInt() and 0xFF
                            val a = blk[b + 3].toInt() and 0xFF
                            out[o] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                        }
                        o++; b += 4
                    }
                }
            }
        }
        return out
    }
}
