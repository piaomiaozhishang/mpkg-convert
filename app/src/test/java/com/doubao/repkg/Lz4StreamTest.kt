package com.doubao.repkg

import com.doubao.repkg.core.Lz4BlockStream
import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 流式 LZ4 block 解码器的守卫测试：
 *  1. 与手头已验证的 lz4_cases.txt 向量逐例一致；
 *  2. 与 lz4-java（压缩方向）做随机模糊对比；
 *  3. 覆盖跨 1 MiB 输出块的用例（视频解压必然跨块）；
 *  4. 截断/大小不符必须报错而不是返回垃圾。
 */
class Lz4StreamTest {

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

    private fun streamDecode(compressed: ByteArray, expected: Long): ByteArray =
        Lz4BlockStream.decompressToByteArray(ByteArrayInputStream(compressed), expected)

    @Test
    fun streamMatchesAllReferenceCases() {
        val text = String(res("lz4_cases.txt"), Charsets.UTF_8)
        var total = 0
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val parts = t.split("\t")
            if (parts.size < 3 || parts[2].isEmpty()) return@forEach
            total++
            val compressed = unhex(parts[1])
            val want = unhex(parts[2])
            val got = streamDecode(compressed, want.size.toLong())
            assertArrayEquals("LZ4 stream case '${parts[0]}'", want, got)
        }
        assertTrue("no LZ4 cases loaded", total > 0)
    }

    /** 生成"重复段 + 随机段"混合的数据，逼出 LZ4 的匹配路径。 */
    private fun mixedData(rnd: java.util.Random, size: Int): ByteArray {
        val data = ByteArray(size)
        var i = 0
        while (i < size) {
            val run = 1 + rnd.nextInt(8000)
            val mode = rnd.nextInt(3)
            val b = rnd.nextInt(256).toByte()
            val end = minOf(size, i + run)
            for (j in i until end) {
                data[j] = when (mode) {
                    0 -> b
                    1 -> ((j % 251)).toByte()
                    else -> rnd.nextInt(256).toByte()
                }
            }
            i = end
        }
        return data
    }

    @Test
    fun fuzzAgainstLz4Java() {
        val compressor = LZ4Factory.fastestInstance().fastCompressor()
        val seeds = longArrayOf(1, 2, 3, 42, 20261014, 777)
        for (seed in seeds) {
            val rnd = java.util.Random(seed)
            val data = mixedData(rnd, 64 * 1024 + rnd.nextInt(256 * 1024))
            val compressed = compressor.compress(data)
            val got = streamDecode(compressed, data.size.toLong())
            assertArrayEquals("fuzz seed=$seed size=${data.size}", data, got)
        }
    }

    @Test
    fun crossesChunkBoundaries() {
        // 数据远大于 1 MiB 输出块：强制多次 flush、匹配跨越块边界
        val compressor = LZ4Factory.fastestInstance().fastCompressor()
        val seeds = longArrayOf(11, 22, 33)
        for (seed in seeds) {
            val rnd = java.util.Random(seed)
            val data = mixedData(rnd, 3 * 1024 * 1024 + rnd.nextInt(512 * 1024))
            val compressed = compressor.compress(data)
            val got = streamDecode(compressed, data.size.toLong())
            assertArrayEquals("chunk-cross seed=$seed size=${data.size}", data, got)
        }
    }

    @Test
    fun longZeroRunsDecode() {
        // 全 0（最典型的重叠匹配 + 大 matchLen 扩展）
        val data = ByteArray(2 * 1024 * 1024)
        val compressed = LZ4Factory.fastestInstance().fastCompressor().compress(data)
        assertArrayEquals(data, streamDecode(compressed, data.size.toLong()))
    }

    @Test
    fun truncatedInputIsRejected() {
        val data = mixedData(java.util.Random(9), 128 * 1024)
        val compressed = LZ4Factory.fastestInstance().fastCompressor().compress(data)
        val truncated = compressed.copyOfRange(0, compressed.size / 2)
        var failed = false
        try {
            streamDecode(truncated, data.size.toLong())
        } catch (e: com.doubao.repkg.core.FormatException) {
            failed = true
        }
        assertTrue("truncated input must fail, not produce garbage", failed)
    }

    @Test
    fun wrongExpectedSizeIsRejected() {
        val data = mixedData(java.util.Random(10), 64 * 1024)
        val compressed = LZ4Factory.fastestInstance().fastCompressor().compress(data)
        var failed = false
        try {
            streamDecode(compressed, data.size.toLong() + 1024)
        } catch (e: com.doubao.repkg.core.FormatException) {
            failed = true
        }
        assertTrue("size mismatch must fail", failed)
    }

    @Test
    fun inMemoryWrapperAgreesWithLz4Java() {
        val compressor = LZ4Factory.fastestInstance().fastCompressor()
        for (seed in longArrayOf(5, 6, 7)) {
            val rnd = java.util.Random(seed)
            val data = mixedData(rnd, 32 * 1024 + rnd.nextInt(64 * 1024))
            val compressed = compressor.compress(data)
            val got = Lz4BlockStream.decompressToByteArray(
                java.io.ByteArrayInputStream(compressed), data.size.toLong()
            )
            assertEquals(data.size.toLong(), got.size.toLong())
            assertArrayEquals(data, got)
        }
    }
}
