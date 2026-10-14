package com.doubao.repkg.core

import java.io.InputStream
import java.io.OutputStream

/**
 * 流式 LZ4 block 解码器（无帧头、无魔数，长度由调用方给出）。
 *
 * 与 [Lz4BlockDecompressor]（整块进内存）不同，这里边解边写出到 [OutputStream]，
 * 内存占用固定为 1 MiB 输出缓冲 + 64 KiB 回引窗口——用于几百 MB～几 GB 的视频 mip，
 * 否则整块解压必然 OOM 闪退。
 *
 * LZ4 block 规则（与 FORMAT_SPEC.md §6 一致）：
 *   token = (literalLen << 4) | (matchLen - 4)，每个 nibble 到 15 后用 0xFF 字节续；
 *   offset = uint16 小端回引距离（1..65535，所以 64 KiB 窗口永远够用）；
 *   最后一个 sequence 只有 literals。
 */
object Lz4BlockStream {

    private const val WINDOW = 1 shl 16          // 64 KiB 回引窗口（LZ4 block 最大距离 65535）
    private const val CHUNK = 1 shl 20           // 1 MiB 输出缓冲

    /**
     * 从 [input] 顺序读压缩数据，解出最多 [stopAt] 字节写到 [out]。
     *
     * @param expected 解压后总大小（来自 mip 头；用于最终一致性校验）
     * @param stopAt   实际要解出的字节数（默认 = expected；提前停止用于只看头部字节）
     * @return 实际写出的字节数（== stopAt，否则抛 [FormatException]）
     */
    fun decompress(
        input: InputStream,
        expected: Long,
        out: OutputStream,
        stopAt: Long = expected
    ): Long {
        if (expected < 0L) throw FormatException("LZ4: negative output size")
        val target = minOf(stopAt, expected)
        if (target <= 0L) return 0L

        val `in` = input.buffered(64 * 1024)
        val window = ByteArray(WINDOW)       // 最近 64 KiB 输出（供回引用）
        var windowFilled = 0                 // window 里有效字节数
        val chunk = ByteArray(CHUNK)         // 当前输出块
        val temp = ByteArray(64 * 1024)      // 从输入读 literals 的临时缓冲
        var pos = 0                          // chunk 写入游标
        var chunkStartAbs = 0L               // chunk[0] 对应的全局输出下标
        var produced = 0L

        fun flushChunk() {
            if (pos == 0) return
            out.write(chunk, 0, pos)
            if (pos >= WINDOW) {
                System.arraycopy(chunk, pos - WINDOW, window, 0, WINDOW)
                windowFilled = WINDOW
            } else {
                val newFilled = minOf(WINDOW, windowFilled + pos)
                val keep = newFilled - pos
                if (keep > 0) System.arraycopy(window, windowFilled - keep, window, 0, keep)
                System.arraycopy(chunk, 0, window, keep, pos)
                windowFilled = newFilled
            }
            pos = 0
            chunkStartAbs = produced
        }

        /** 把一个回引匹配拷进 chunk（跨 chunk/窗口边界都能处理）。 */
        fun copyMatch(offset: Int, length: Int) {
            if (offset <= 0) throw FormatException("LZ4: invalid offset $offset")
            if (offset > produced) throw FormatException("LZ4: offset ${offset} beyond output $produced")
            var remaining = length
            while (remaining > 0) {
                if (pos == CHUNK) flushChunk()
                val can = minOf(remaining, CHUNK - pos, (target - produced).toInt())
                if (can <= 0) return
                val srcAbs = produced - offset          // 源起点（全局）
                // 是否与目标区重叠（重叠时必须逐字节传播，不能用 memmove 语义）
                val overlap = offset < can
                when {
                    offset == 1 -> {
                        // 最常见的 RLE：等值填充
                        val b = if (pos > 0) chunk[pos - 1] else window[windowFilled - 1]
                        java.util.Arrays.fill(chunk, pos, pos + can, b)
                    }
                    srcAbs >= chunkStartAbs && !overlap -> {
                        // 源在当前 chunk 内且不重叠：直接搬
                        System.arraycopy(chunk, (srcAbs - chunkStartAbs).toInt(), chunk, pos, can)
                    }
                    srcAbs >= chunkStartAbs -> {
                        // 源在当前 chunk 内且重叠：逐字节（新写的字节成为后续源）
                        val srcIdx = (srcAbs - chunkStartAbs).toInt()
                        for (i in 0 until can) chunk[pos + i] = chunk[srcIdx + i]
                    }
                    !overlap -> {
                        // 源在 window 里（可能跨到当前 chunk 开头），不重叠：分段搬
                        val winBase = chunkStartAbs - windowFilled
                        val winIdx = (srcAbs - winBase).toInt()
                        if (winIdx < 0) throw FormatException("LZ4: reference beyond window")
                        val fromWin = minOf(can, windowFilled - winIdx)
                        if (fromWin > 0) {
                            System.arraycopy(window, winIdx, chunk, pos, fromWin)
                        }
                        if (can > fromWin) {
                            System.arraycopy(chunk, 0, chunk, pos + fromWin, can - fromWin)
                        }
                    }
                    else -> {
                        // 源在 window/chunk 边界且重叠：逐字节
                        val winBase = chunkStartAbs - windowFilled
                        for (i in 0 until can) {
                            val abs = produced + i - offset
                            chunk[pos + i] =
                                if (abs >= chunkStartAbs) chunk[(abs - chunkStartAbs).toInt()]
                                else window[(abs - winBase).toInt()]
                        }
                    }
                }
                pos += can
                produced += can
                remaining -= can
            }
        }

        /** 写 literals（从输入顺序读 [length] 字节，最多写到 target 为止）。 */
        fun copyLiterals(length: Int) {
            var done = 0
            while (done < length) {
                if (pos == CHUNK) flushChunk()
                val want = minOf(length - done, CHUNK - pos, (target - produced).toInt())
                if (want <= 0) return
                val read = `in`.read(temp, 0, minOf(want, temp.size))
                if (read < 0) throw FormatException("LZ4: truncated literals")
                System.arraycopy(temp, 0, chunk, pos, read)
                pos += read
                produced += read
                done += read
            }
        }

        while (produced < target) {
            val token = `in`.read()
            if (token < 0) throw FormatException("LZ4: unexpected end of input")

            var litLen = (token shr 4) and 0xF
            var matchNibble = token and 0xF
            if (litLen == 15) {
                while (true) {
                    val b = `in`.read()
                    if (b < 0) throw FormatException("LZ4: truncated literal length")
                    litLen += b
                    if (b != 0xFF) break
                }
            }

            copyLiterals(litLen)
            if (produced >= target) break    // 最后一个 sequence 只有 literals

            val b0 = `in`.read()
            val b1 = `in`.read()
            if (b0 < 0 || b1 < 0) throw FormatException("LZ4: truncated offset")
            val offset = b0 or (b1 shl 8)

            if (matchNibble == 15) {
                while (true) {
                    val b = `in`.read()
                    if (b < 0) throw FormatException("LZ4: truncated match length")
                    matchNibble += b
                    if (b != 0xFF) break
                }
            }
            copyMatch(offset, matchNibble + 4)
            if (produced > expected) throw FormatException("LZ4: output exceeds expected size")
        }

        flushChunk()
        if (produced != target) {
            throw FormatException("LZ4: produced $produced bytes, expected $target")
        }
        return produced
    }

    /** 便捷封装：解到内存（小 payload 用）；[stopAt] 可提前停止（只看头部字节）。 */
    fun decompressToByteArray(input: InputStream, expected: Long, stopAt: Long = expected): ByteArray {
        val cap = minOf(stopAt, expected).coerceAtLeast(0L)
            .coerceAtMost(64L * 1024 * 1024).toInt()
        val out = java.io.ByteArrayOutputStream(maxOf(64, cap))
        decompress(input, expected, out, stopAt)
        return out.toByteArray()
    }
}
