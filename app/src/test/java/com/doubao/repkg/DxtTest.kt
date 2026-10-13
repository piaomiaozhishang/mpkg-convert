package com.doubao.repkg

import com.doubao.repkg.core.Dxt
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Verifies the S3TC block decompressor against hand-built blocks:
 *  - DXT1 solid red block (color0 == color1, all indices 0)
 *  - DXT5 solid red block with fully opaque alpha
 */
class DxtTest {

    private fun expectedSolidRed(): ByteArray {
        val out = ByteArray(4 * 4 * 4)
        for (i in 0 until 16) {
            out[4 * i] = (-1).toByte()   // R
            out[4 * i + 1] = 0           // G
            out[4 * i + 2] = 0           // B
            out[4 * i + 3] = (-1).toByte() // A
        }
        return out
    }

    @Test
    fun dxt1SolidRedBlock() {
        // color0 = color1 = 0xF800 (red in RGB565), indices all 0.
        val block = byteArrayOf(
            0x00, 0xF8.toByte(), 0x00, 0xF8.toByte(), // colors
            0x00, 0x00, 0x00, 0x00                    // indices
        )
        val rgba = Dxt.decompress(4, 4, block, Dxt.DXT1)
        assertArrayEquals(expectedSolidRed(), rgba)
    }

    @Test
    fun dxt5SolidRedBlock() {
        // alpha0 = alpha1 = 255 -> all alpha 255;
        // color part identical to DXT1 solid red.
        val block = byteArrayOf(
            (-1).toByte(), (-1).toByte(),               // alpha endpoints
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,         // alpha indices (ignored, codebook uniform)
            0x00, 0xF8.toByte(), 0x00, 0xF8.toByte(),   // colors
            0x00, 0x00, 0x00, 0x00                      // color indices
        )
        val rgba = Dxt.decompress(4, 4, block, Dxt.DXT5)
        assertArrayEquals(expectedSolidRed(), rgba)
    }

    @Test
    fun dxt1NonMultipleOfFourIsClipped() {
        // 2x2 image inside a 4x4 solid red block: edge clipping must not crash.
        val block = byteArrayOf(
            0x00, 0xF8.toByte(), 0x00, 0xF8.toByte(),
            0x00, 0x00, 0x00, 0x00
        )
        val rgba = Dxt.decompress(2, 2, block, Dxt.DXT1)
        assertEquals4(2 * 2 * 4, rgba.size)
    }

    private fun assertEquals4(expected: Int, actual: Int) {
        if (expected != actual) throw AssertionError("expected $expected but got $actual")
    }
}
