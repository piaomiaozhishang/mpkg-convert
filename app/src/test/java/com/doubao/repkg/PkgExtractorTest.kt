package com.doubao.repkg

import com.doubao.repkg.core.AssetKind
import com.doubao.repkg.core.ByteArraySeekableData
import com.doubao.repkg.core.Extractor
import com.doubao.repkg.core.PkgReader
import com.doubao.repkg.core.TexAsset
import com.doubao.repkg.core.TexAssetDecoder
import com.doubao.repkg.core.TexVideoExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PkgExtractorTest {

    private fun le32(n: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

    private fun i32s(s: ByteArray): ByteArray = le32(s.size) + s

    private fun fakeMp4(marker: String): ByteArray {
        val payload = marker.toByteArray() + ByteArray(64) { (it % 251).toByte() }
        val body = "ftypisom".toByteArray() + ByteArray(4) + payload
        val size = ByteBuffer.allocate(4).putInt(8 + body.size).array()
        return size + body
    }

    /** 2x2 RGBA8888 static texture: red, green, blue, white. */
    private fun rawRgbaPixels(): ByteArray {
        val rgba = ByteArray(2 * 2 * 4)
        rgba[0] = -1; rgba[1] = 0; rgba[2] = 0; rgba[3] = -1   // red
        rgba[4] = 0; rgba[5] = -1; rgba[6] = 0; rgba[7] = -1   // green
        rgba[8] = 0; rgba[9] = 0; rgba[10] = -1; rgba[11] = -1 // blue
        rgba[12] = -1; rgba[13] = -1; rgba[14] = -1; rgba[15] = -1 // white
        return rgba
    }

    private fun makeStaticTex(pixels: ByteArray, width: Int, height: Int): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0)        // RGBA8888
        out += le32(0)        // flags (not video)
        out += le32(width); out += le32(height)
        out += le32(width); out += le32(height)
        out += le32(0)
        out += "TEXB0003\u0000".toByteArray()
        out += le32(1)        // image count
        out += le32(-1)       // imageFormat unknown
        out += le32(1)        // mipmap count (v3)
        out += le32(width); out += le32(height)
        out += le32(0); out += le32(pixels.size); out += le32(pixels.size)
        out += pixels
        return out
    }

    /** Desktop (PKGV) video tex: TEXB0004 video with the v4 param block. */
    private fun makeDesktopVideoTex(mp4: ByteArray): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(32)
        out += le32(1920); out += le32(1080); out += le32(1920); out += le32(1080)
        out += le32(0)
        out += "TEXB0004\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(1)
        out += le32(1)        // mipmap count (v4)
        out += le32(1); out += le32(2)
        out += byteArrayOf(0) // condition NString
        out += le32(1)
        out += le32(1920); out += le32(1080)
        out += le32(0); out += le32(mp4.size); out += le32(mp4.size)
        out += mp4
        return out
    }

    /**
     * Mobile (PKGM) video tex: TEXB0004 but the mipmap uses the compact v3
     * layout (no param block) — this is what real mobile .mpkg files use and
     * what previously made exported videos unplayable.
     */
    private fun makeMobileVideoTex(mp4: ByteArray): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(32)
        out += le32(1920); out += le32(1080); out += le32(1920); out += le32(1080)
        out += le32(0)
        out += "TEXB0004\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(1)
        out += le32(1)        // mipmap count
        out += le32(1920); out += le32(1080)   // v3 layout, no params
        out += le32(0); out += le32(mp4.size); out += le32(mp4.size)
        out += mp4
        return out
    }

    /**
     * Desktop (PKGV) video tex whose condition JSON is intentionally longer than
     * the 4 KB peek window, to exercise the "header exceeds prefix" fallback.
     */
    private fun makeDesktopVideoTexLongCondition(mp4: ByteArray, jsonLen: Int): ByteArray {
        var out = "TEXV0005\u0000".toByteArray()
        out += "TEXI0001\u0000".toByteArray()
        out += le32(0); out += le32(32)
        out += le32(1920); out += le32(1080); out += le32(1920); out += le32(1080)
        out += le32(0)
        out += "TEXB0004\u0000".toByteArray()
        out += le32(1); out += le32(-1); out += le32(1)
        out += le32(1)                     // mipmap count (v4)
        out += le32(1); out += le32(2)
        out += ByteArray(jsonLen) { 'a'.code.toByte() }
        out += byteArrayOf(0)              // NUL-terminated condition json
        out += le32(1)
        out += le32(1920); out += le32(1080)
        out += le32(0); out += le32(mp4.size); out += le32(mp4.size)
        out += mp4
        return out
    }

    private fun assemble(magic: String, entries: List<Pair<String, ByteArray>>): ByteArray {        var header = i32s(magic.toByteArray())
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

    // --- PkgReader ---

    @Test
    fun parsesHeader() {
        val pkg = assemble("PKGV0005", listOf("videos/direct.mp4" to fakeMp4("D")))
        val archive = PkgReader.read(ByteArraySeekableData(pkg))
        assertEquals("PKGV0005", archive.magic)
        assertEquals(1, archive.entries.size)
        assertEquals(".mp4", archive.entries[0].extension)
    }

    @Test
    fun parsesRealMobileMpkgMagic() {
        val pkg = assemble("PKGM0018", listOf("videos/direct.mp4" to fakeMp4("D")))
        val archive = PkgReader.read(ByteArraySeekableData(pkg))
        assertEquals("PKGM0018", archive.magic)
    }

    // --- Video wallpaper ---

    @Test
    fun videoWallpaperMainIsVideo() {
        val pkg = assemble(
            "PKGV0005",
            listOf(
                "videos/direct.mp4" to fakeMp4("DIRECT"),
                "materials/clip.tex" to makeDesktopVideoTex(fakeMp4("FROMTEX"))
            )
        )
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertTrue(r.isVideo)
        assertNotNull(r.main)
        assertEquals(AssetKind.VIDEO, r.main!!.kind)
    }

    @Test
    fun mobileVideoTexDecodesToPlayableMp4() {
        val mp4 = fakeMp4("MOBILEVIDEO")
        val pkg = assemble(
            "PKGM0019",
            listOf("materials/clip.tex" to makeMobileVideoTex(mp4))
        )
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertTrue(r.isVideo)
        assertNotNull(r.main)
        // Byte-identical to the embedded MP4 -> playable, not TEX bytes.
        assertArrayEquals(mp4, r.main!!.bytes)
    }

    // --- Static wallpaper ---

    @Test
    fun staticWallpaperMainIsOriginal() {
        val pixels = rawRgbaPixels()
        val pkg = assemble(
            "PKGM0019",
            listOf(
                "materials/wallpaper.tex" to makeStaticTex(pixels, 2, 2),
                "image.png" to "PNG".toByteArray()
            )
        )
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertTrue(!r.isVideo)
        assertNotNull(r.main)
        assertTrue(r.main!!.isPrimary)
        assertEquals("materials/wallpaper.tex", r.main!!.sourceEntry)
        assertArrayEquals(pixels, r.main!!.pixelsRgba)
    }

    @Test
    fun unrelatedPlainImageIsDropped() {
        // image.png is neither the main art nor a preview path -> dropped.
        val pkg = assemble(
            "PKGM0019",
            listOf(
                "materials/wallpaper.tex" to makeStaticTex(rawRgbaPixels(), 2, 2),
                "image.png" to "PNG".toByteArray()
            )
        )
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertNull(r.preview)
    }

    @Test
    fun previewPictureIsDetected() {
        val previewJpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xE0.toByte())
        val pkg = assemble(
            "PKGM0019",
            listOf(
                "materials/wallpaper.tex" to makeStaticTex(rawRgbaPixels(), 2, 2),
                "preview.jpg" to previewJpg
            )
        )
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertNotNull(r.preview)
        assertEquals("preview.jpg", r.preview!!.sourceEntry)
        assertArrayEquals(previewJpg, r.preview!!.bytes)
    }

    // --- Legacy / decoder-level ---

    @Test
    fun peekFromPrefixHandlesLargeStaticTex() {
        // The .tex entry is far larger than the 4 KB peek window; the header must
        // still be read correctly (header-only prefix read, no full-entry load).
        val pixels = ByteArray(128 * 128 * 4) { (it % 251).toByte() }
        val tex = makeStaticTex(pixels, 128, 128)
        assertTrue("entry should exceed the peek window", tex.size > 4096)
        val pkg = assemble("PKGM0019", listOf("materials/wallpaper.tex" to tex))
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertNotNull(r.main)
        assertEquals("materials/wallpaper.tex", r.main!!.sourceEntry)
        assertArrayEquals(pixels, r.main!!.pixelsRgba)
    }

    @Test
    fun peekFallsBackWhenHeaderExceedsThePrefix() {
        // Header (a >4 KB condition JSON) does not fit the peek window, so the
        // decoder must fall back to a full read rather than silently failing.
        val mp4 = fakeMp4("LONGCOND")
        val tex = makeDesktopVideoTexLongCondition(mp4, 5000)
        assertTrue("header should exceed the peek window", tex.size > 4096)
        val pkg = assemble("PKGV0005", listOf("materials/clip.tex" to tex))
        val r = Extractor.analyze(ByteArraySeekableData(pkg))
        assertTrue("video wallpaper expected", r.isVideo)
        assertNotNull(r.main)
        assertArrayEquals(mp4, r.main!!.bytes)
    }

    @Test
    fun texVideoIsByteIdentical() {
        val mp4 = fakeMp4("FROMTEX")
        val extracted = TexVideoExtractor.extract(makeDesktopVideoTex(mp4))
        assertNotNull(extracted)
        assertArrayEquals(mp4, extracted)
    }

    @Test
    fun texAssetDecoderReadsStaticImage() {
        val pixels = rawRgbaPixels()
        val asset = TexAssetDecoder.decode(makeStaticTex(pixels, 2, 2))
        assertTrue(asset is TexAsset.Image)
        asset as TexAsset.Image
        assertEquals(2, asset.width)
        assertEquals(2, asset.height)
        assertArrayEquals(pixels, asset.rgba)
    }

    @Test
    fun malformedInputIsRejected() {
        val result = try {
            TexVideoExtractor.extract(ByteArray(30) { 0 })
        } catch (e: RuntimeException) {
            null
        }
        assertNull(result)
    }
}
