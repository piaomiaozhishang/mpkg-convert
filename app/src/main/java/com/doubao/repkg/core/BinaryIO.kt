package com.doubao.repkg.core

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * Random-access binary data source used by the parsers.
 * Two implementations are provided so the exact same parsing code runs on
 * Android (file channel) and in plain JVM unit tests (byte array).
 */
interface SeekableData {
    val size: Long

    /** Read exactly [length] bytes starting at absolute [offset]. */
    fun readAt(offset: Long, length: Int): ByteArray
}

/** Backed by a seekable [FileChannel]. */
class ChannelSeekableData(private val channel: FileChannel) : SeekableData {
    override val size: Long get() = channel.size()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length == 0) return EMPTY
        val out = ByteBuffer.allocate(length)
        var pos = offset
        while (out.hasRemaining()) {
            channel.position(pos)
            val read = channel.read(out)
            if (read < 0) throw EOFException("Unexpected end of file at offset $pos")
            pos += read
        }
        return out.array()
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/** Backed by an in-memory byte array (used in unit tests). */
class ByteArraySeekableData(private val data: ByteArray) : SeekableData {
    override val size: Long get() = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length == 0) return EMPTY
        if (offset < 0 || offset > data.size) throw EOFException("offset out of range: $offset")
        if (offset + length > data.size) throw EOFException("read past end of data")
        return data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/**
 * Little-endian sequential reader with a movable cursor.
 * Mirrors the BinaryReader extension methods used by the original RePKG.
 */
class LeReader(private val src: SeekableData) {
    var position: Long = 0
        private set

    private fun readRaw(n: Int): ByteArray {
        val bytes = src.readAt(position, n)
        position += n
        return bytes
    }

    /** Read exactly [n] bytes at the cursor and advance it. */
    fun readBytes(n: Int): ByteArray = readRaw(n)

    /** Read a signed 32-bit little-endian integer. */
    fun readInt32(): Int {
        val b = readRaw(4)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    /** Read an unsigned 32-bit little-endian value as a long. */
    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    /**
     * String prefixed by a signed 32-bit length (see ReadStringI32Size).
     */
    fun readStringI32Size(maxLength: Int = -1): String {
        var size = readInt32()
        if (size < 0) throw FormatException("String length cannot be negative")
        if (maxLength > -1 && size > maxLength) size = maxLength
        val bytes = readRaw(size)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * NUL-terminated string (see ReadNString). Reads byte by byte; only used
     * for tiny magic identifiers.
     */
    fun readNString(maxLength: Int = -1): String {
        val sb = StringBuilder(if (maxLength > 0) maxLength else 16)
        while (true) {
            val b = readRaw(1)[0]
            if (b.toInt() == 0) break
            sb.append(b.toInt().and(0xFF).toChar())
            if (maxLength > -1 && sb.length >= maxLength) break
        }
        return sb.toString()
    }
}

class FormatException(message: String) : RuntimeException(message)

/** Convenience to build a little-endian int in tests/builders. */
internal fun Int.toLeBytes(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
