package com.doubao.repkg

import com.doubao.repkg.core.FormatException
import com.doubao.repkg.core.Lz4BlockDecompressor
import net.jpountz.lz4.LZ4Factory

/**
 * Raw LZ4 block decompression backed by lz4-java.
 * Matches K4os LZ4Codec.Decode used by the original RePKG.
 */
object AndroidLz4 : Lz4BlockDecompressor {

    private val decompressor by lazy { LZ4Factory.fastestInstance().fastDecompressor() }

    override fun decompress(source: ByteArray, knownDecompressedLength: Int): ByteArray {
        val dest = ByteArray(knownDecompressedLength)
        val compressedRead = decompressor.decompress(source, 0, dest, 0, knownDecompressedLength)
        if (compressedRead != source.size) {
            throw FormatException("LZ4 size mismatch: read $compressedRead of ${source.size}")
        }
        return dest
    }
}
