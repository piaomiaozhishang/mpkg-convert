package com.doubao.repkg.core

/**
 * A single file stored inside a Wallpaper Engine package.
 *
 * @param path full path as recorded in the package (may contain sub-folders)
 * @param dataOffset absolute byte offset of the entry data within the source
 * @param length data length in bytes
 */
data class PkgEntry(
    val path: String,
    val dataOffset: Long,
    val length: Long
) {
    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val extension: String
        get() {
            val n = fileName
            val dot = n.lastIndexOf('.')
            return if (dot < 0) "" else n.substring(dot).lowercase()
        }
    val nameWithoutExtension: String
        get() {
            val n = fileName
            val dot = n.lastIndexOf('.')
            return if (dot < 0) n else n.substring(0, dot)
        }
}

data class PkgArchive(
    val magic: String,
    val headerSize: Long,
    val entries: List<PkgEntry>
)

/**
 * Parser for Wallpaper Engine PKG / mobile MPKG containers.
 *
 * Layout (all integers little-endian), shared by the PKGV and PKGM families:
 *   int32  magicLength + magic bytes        (e.g. "PKGV0005" / "PKGM0018")
 *   int32  entryCount
 *   repeat entryCount:
 *       int32 pathLength + path bytes
 *       int32 offset    (relative to the start of the data region)
 *       int32 length
 *   <data region begins here>
 */
object PkgReader {

    private const val MAX_ENTRIES = 100_000

    /** Container family: PKGV#### (desktop scene.pkg) and PKGM#### (.mpkg mobile). */
    private val PKG_MAGIC = Regex("""PKG[VM]\d{4}""")

    fun read(src: SeekableData): PkgArchive {
        val r = LeReader(src)
        val packageStart = r.position

        val magic = r.readStringI32Size(maxLength = 32)
        if (!PKG_MAGIC.matches(magic)) {
            throw FormatException("Not a Wallpaper Engine package (magic='$magic')")
        }

        val entryCount = r.readInt32()
        if (entryCount < 0 || entryCount > MAX_ENTRIES) {
            throw FormatException("Invalid entry count: $entryCount")
        }

        // First pass: relative offsets (resolved once the data start is known).
        data class Raw(val path: String, val relOffset: Long, val length: Long)

        val raw = ArrayList<Raw>(entryCount)
        repeat(entryCount) {
            val path = r.readStringI32Size(maxLength = 255)
            val relOffset = r.readUInt32()
            val length = r.readUInt32()
            raw.add(Raw(path, relOffset, length))
        }

        val dataStart = r.position
        val headerSize = dataStart - packageStart

        val entries = raw.map {
            PkgEntry(
                path = it.path.replace('\\', '/'),
                dataOffset = dataStart + it.relOffset,
                length = it.length
            )
        }

        return PkgArchive(magic = magic, headerSize = headerSize, entries = entries)
    }

    /** Read the raw bytes of an entry from a data source. */
    fun readEntryBytes(src: SeekableData, entry: PkgEntry): ByteArray {
        val len = entry.length
        if (len > Int.MAX_VALUE) {
            throw FormatException("Entry too large (>2GB): ${entry.path}")
        }
        return src.readAt(entry.dataOffset, len.toInt())
    }

    /**
     * Read only the first [maxBytes] of an entry. Used to peek a ".tex" header
     * without pulling a multi-hundred-MB video texture into memory.
     */
    fun readEntryPrefix(src: SeekableData, entry: PkgEntry, maxBytes: Int): ByteArray {
        val len = minOf(entry.length, maxBytes.toLong()).toInt()
        return src.readAt(entry.dataOffset, len)
    }
}
