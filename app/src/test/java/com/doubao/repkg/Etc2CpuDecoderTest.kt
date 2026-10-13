package com.doubao.repkg

import com.doubao.repkg.core.Etc2CpuDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ETC2_RGBA8 CPU fallback decoder and the LZ4 block path against the
 * ground-truth vectors that were verified against the Python oracle
 * (see the wallpaper-engine handoff package). Vectors live in
 * `src/test/resources/testdata/`.
 */
class Etc2CpuDecoderTest {

    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("testdata/$name")
            ?.readBytes()
            ?: error("missing test resource testdata/$name")

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or
                    Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    @Test
    fun etc2_blocks_match_reference_vectors() {
        val blocks = res("selftest_blocks.bin")
        val expected = res("selftest_expected.bin")
        val n = blocks.size / 16
        assertTrue("no blocks loaded", n > 0)
        assertEquals("expected buffer size", n * 64, expected.size)

        val out = ByteArray(64)
        var mismatches = 0
        for (i in 0 until n) {
            Etc2CpuDecoder.decodeBlock(blocks, i * 16, out, 0)
            for (j in 0 until 64) {
                if (out[j] != expected[i * 64 + j]) {
                    mismatches++
                    if (mismatches <= 3) {
                        println("ETC2 block #$i byte $j: got ${out[j].toInt() and 0xFF}, " +
                                "want ${expected[i * 64 + j].toInt() and 0xFF}")
                    }
                    break
                }
            }
        }
        assertEquals("ETC2_RGBA8 block vectors ($n blocks)", 0, mismatches)
    }

    @Test
    fun etc2_decodeArgb_is_row_major_rgba() {
        // decodeArgb must pack RGBA row-major the same way Bitmap.setPixels expects.
        val blocks = res("selftest_blocks.bin")
        val expected = res("selftest_expected.bin")
        val argb = Etc2CpuDecoder.decodeArgb(blocks.copyOfRange(0, 16), 4, 4)
        assertEquals(16, argb.size)
        for (i in 0 until 16) {
            val o = i * 4
            val want = ((expected[o + 3].toInt() and 0xFF) shl 24) or
                    ((expected[o].toInt() and 0xFF) shl 16) or
                    ((expected[o + 1].toInt() and 0xFF) shl 8) or
                    (expected[o + 2].toInt() and 0xFF)
            assertEquals("pixel $i", want, argb[i])
        }
    }

    @Test
    fun lz4_block_cases_match() {
        val text = String(res("lz4_cases.txt"), Charsets.UTF_8)
        var total = 0
        var mismatches = 0
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val parts = t.split("\t")
            if (parts.size < 3 || parts[2].isEmpty()) return@forEach
            total++
            val compressed = unhex(parts[1])
            val want = unhex(parts[2])
            val got = AndroidLz4.decompress(compressed, want.size)
            if (!got.contentEquals(want)) {
                mismatches++
                println("LZ4 case '${parts[0]}' mismatch")
            }
        }
        assertTrue("no LZ4 cases loaded", total > 0)
        assertEquals("LZ4 block cases ($total)", 0, mismatches)
    }
}
