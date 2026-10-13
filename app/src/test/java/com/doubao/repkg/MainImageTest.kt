package com.doubao.repkg

import com.doubao.repkg.core.AssetKind
import com.doubao.repkg.core.ByteArraySeekableData
import com.doubao.repkg.core.Extractor
import com.doubao.repkg.core.ImageRanker
import com.doubao.repkg.core.TexAsset
import com.doubao.repkg.core.TexAssetDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Verifies primary-image detection: the big art texture wins, never a preview. */
class MainImageTest {

    private fun le32(n: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

    private fun i32s(s: ByteArray): ByteArray = le32(s.size) + s

    /** Colourful gradient pixels that pass the grayscale/flat gate. */
    private fun gradientPixels(w: Int, h: Int): ByteArray {
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            px[o] = (x * 255 / (w - 1)).toByte()
            px[o + 1] = (y * 255 / (h - 1)).toByte()
            px[o + 2] = 128.toByte()
            px[o + 3] = (-1).toByte()
        }
        return px
    }

    private fun rawTex(pixels: ByteArray, w: Int, h: Int): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(0)
        out += le32(w); out += le32(h); out += le32(w); out += le32(h)
        out += le32(0)
        out += "TEXB0003\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(1)
        out += le32(w); out += le32(h)
        out += le32(0); out += le32(pixels.size); out += le32(pixels.size)
        out += pixels
        return out
    }

    /** PNG-in-tex: container announces FIF_PNG (13), mip0 bytes are the "PNG". */
    private fun pngTex(pngBytes: ByteArray, w: Int, h: Int): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(0)
        out += le32(w); out += le32(h); out += le32(w); out += le32(h)
        out += le32(0)
        out += "TEXB0003\u0000".toByteArray()
        out += le32(1); out += le32(13); out += le32(1)
        out += le32(w); out += le32(h)
        out += le32(0); out += le32(pngBytes.size); out += le32(pngBytes.size)
        out += pngBytes
        return out
    }

    /** Non-video ETC2_EAC texture: texFormat=5, TEXB0004 (isVideo=0 -> v3 layout). */
    private fun etc2Tex(blockBytes: ByteArray, w: Int, h: Int): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(5); out += le32(2)
        out += le32(w); out += le32(h); out += le32(w); out += le32(h)
        out += le32(0)
        out += "TEXB0004\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(0)
        out += le32(1)
        out += le32(w); out += le32(h); out += le32(0)
        out += le32(blockBytes.size); out += le32(blockBytes.size)
        out += blockBytes
        return out
    }

    private fun packageWithMainAndPreview(): ByteArray {
        val main = rawTex(gradientPixels(8, 8), 8, 8)
        val preview = pngTex("\u0089PNG fake".toByteArray(), 4, 4)
        val entries = listOf(
            "textures/main.tex" to main,
            "textures/preview.tex" to preview
        )
        var header = i32s("PKGM0018".toByteArray())
        header += le32(entries.size)
        var data = ByteArray(0)
        var cursor = 0
        for ((p, b) in entries) {
            header += i32s(p.toByteArray())
            header += le32(cursor)
            header += le32(b.size)
            data += b
            cursor += b.size
        }
        return header + data
    }

    @Test
    fun peekReportsDimensionsAndFormat() {
        val peek = TexAssetDecoder.peek(rawTex(gradientPixels(4, 4), 4, 4))
        assertTrue(peek != null)
        assertEquals(4, peek!!.width)
        assertEquals(4, peek.height)
        assertEquals(0, peek.texFormat)
        assertFalse(peek.isVideo)
    }

    @Test
    fun rankerPutsBigArtFirst() {
        val ranked = ImageRanker.rank(listOf(
            ImageRanker.Candidate("textures/preview.tex", 4, 4, 0, 13),
            ImageRanker.Candidate("textures/main.tex", 8, 8, 0, -1)
        ))
        assertEquals("textures/main.tex", ranked.first().candidate.path)
    }

    @Test
    fun artPathDetection() {
        assertTrue(ImageRanker.isArtPath("textures/main.tex"))
        assertFalse(ImageRanker.isArtPath("textures/preview.tex"))
        assertFalse(ImageRanker.isArtPath("textures/ui/button.tex"))
        assertFalse(ImageRanker.isArtPath("materials/masks/depth.tex"))
    }

    @Test
    fun primaryIsMainNotPreview() {
        val r = Extractor.analyze(ByteArraySeekableData(packageWithMainAndPreview()))
        // Main original = main.tex; preview exposed separately, not as main.
        assertEquals("textures/main.tex", r.main!!.sourceEntry)
        assertTrue(r.main!!.isPrimary)
        assertEquals("textures/preview.tex", r.preview!!.sourceEntry)
    }

    @Test
    fun aspectGateRejectsStrips() {
        assertTrue(ImageRanker.aspectAcceptable(1080, 1920)) // 9:16 phone
        assertFalse(ImageRanker.aspectAcceptable(2000, 100))  // wide strip
        assertFalse(ImageRanker.aspectAcceptable(100, 2000))  // tall strip
    }

    @Test
    fun etc2ReturnsCompressedBytesForGpuDecode() {
        val w = 8; val h = 8
        val blocks = ByteArray(w * h) { (it % 200).toByte() }
        val asset = TexAssetDecoder.decode(etc2Tex(blocks, w, h))
        assertTrue(asset is TexAsset.Image)
        asset as TexAsset.Image
        assertTrue(asset.compressedBytes != null)
        assertEquals(w * h, asset.compressedBytes!!.size)
    }
}
