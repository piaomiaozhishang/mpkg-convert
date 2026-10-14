package com.doubao.repkg.core

/** Kind of an extracted asset. */
enum class AssetKind { VIDEO, IMAGE }

/** One exportable result (a video or a picture). */
data class ExtractedAsset(
    val kind: AssetKind,
    /** Suggested file name, including extension. */
    val fileName: String,
    /** Path of the package entry the asset came from. */
    val sourceEntry: String,
    /** Ready-to-save file bytes (mp4 / encoded image). Null when pixels/compressed are used. */
    val bytes: ByteArray? = null,
    /** Decoded RGBA8888 pixels needing Bitmap encoding (raw/DXT textures). */
    val pixelsRgba: ByteArray? = null,
    /** Compressed block stream (ETC2) needing platform GPU decoding. */
    val compressedBytes: ByteArray? = null,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
    /** True for the wallpaper's main full-resolution content. */
    val isPrimary: Boolean = false,
    /**
     * True when the payload is still inside the package and must be streamed out
     * with [Extractor.extractVideoTo] (large videos are never held in memory).
     */
    val fromPackage: Boolean = false,
    /** Decoded payload size in bytes (videos), for progress/size display. */
    val decodedSize: Long = 0L,
    val note: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedAsset) return false
        return kind == other.kind && fileName == other.fileName && sourceEntry == other.sourceEntry
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + sourceEntry.hashCode()
        return result
    }
}

/**
 * The analyzed contents of one wallpaper package: a single main piece
 * (original picture OR video) plus, when available, a preview picture.
 * Everything else (UI icons, masks, widgets) is intentionally dropped.
 */
class WallpaperResult(
    val isVideo: Boolean,
    /** Main content: original image (static) or video (dynamic). May be null if undecodable. */
    val main: ExtractedAsset?,
    /** Preview picture, when the package provides one. */
    val preview: ExtractedAsset?,
    /** True when the container was a mobile PKGM package. */
    val isMobile: Boolean = false
)

/** Progress callback while scanning a package. */
fun interface ExtractProgress {
    fun onProgress(current: Int, total: Int, message: String)
}

/** Image extensions saved verbatim when found as plain package entries. */
private val PLAIN_IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".tga", ".dds")

/** Bytes of a .tex entry read to peek its header (a header is far smaller than this). */
private const val PEEK_PREFIX_BYTES = 4096

/**
 * High level facade: analyze an mpkg/pkg and return only the wallpaper's main
 * content and its preview. The caller decides which piece the user exports.
 */
object Extractor {

    fun analyze(
        src: SeekableData,
        lz4: Lz4BlockDecompressor? = null,
        progress: ExtractProgress? = null
    ): WallpaperResult {
        val archive = PkgReader.read(src)
        val isMobile = archive.magic.startsWith("PKGM")
        val total = archive.entries.size

        // Pass 1: classify entries (tex headers are peeked, no pixel payload).
        val videoEntries = ArrayList<Pair<PkgEntry, Long>>()   // entry -> decoded size
        val imageTexCandidates = ArrayList<ImageRanker.Candidate>()
        val plainImageEntries = ArrayList<PkgEntry>()

        archive.entries.forEachIndexed { index, entry ->
            progress?.onProgress(index, total, entry.path)
            when {
                entry.extension == ".mp4" -> videoEntries.add(entry to entry.length)
                entry.extension == ".tex" -> {
                    val peek = peekTexHeader(src, entry, isMobile)
                    when {
                        peek == null -> { /* unreadable */ }
                        peek.isVideo -> videoEntries.add(
                            entry to (peek.decodedSize.takeIf { it > 0 } ?: entry.length)
                        )
                        else -> imageTexCandidates.add(
                            ImageRanker.Candidate(
                                path = entry.path,
                                width = peek.width,
                                height = peek.height,
                                texFormat = peek.texFormat,
                                imageFormat = peek.imageFormat
                            )
                        )
                    }
                }
                entry.extension in PLAIN_IMAGE_EXTENSIONS -> plainImageEntries.add(entry)
                else -> { /* json, audio, scripts: ignored */ }
            }
        }

        val usedNames = HashSet<String>()
        val isVideo = videoEntries.isNotEmpty()

        fun entryFor(path: String) = archive.entries.first { it.path == path }

        // --- Main content ---
        var main: ExtractedAsset? = null
        if (isVideo) {
            // Pick the largest video by its DECODED size (from the peeked mip
            // header — the payload is never loaded into memory here).
            val best = videoEntries.maxByOrNull { it.second }
            if (best != null) {
                main = ExtractedAsset(
                    AssetKind.VIDEO,
                    uniqueName(usedNames, best.first.nameWithoutExtension, "mp4"),
                    best.first.path,
                    bytes = null,
                    fromPackage = true,
                    decodedSize = best.second,
                    isPrimary = true,
                    note = "视频"
                )
            }
        } else {
            // Best (largest, art-like) picture texture, best first.
            val ranked = ImageRanker.rank(imageTexCandidates)
            for (rankedItem in ranked) {
                val entry = entryFor(rankedItem.candidate.path)
                val raw = PkgReader.readEntryBytes(src, entry)
                val asset = try {
                    TexAssetDecoder.decode(raw, lz4, isMobile)
                } catch (e: RuntimeException) {
                    null
                }
                if (asset is TexAsset.Image) {
                    main = toImageExtracted(usedNames, entry, asset, primary = true,
                        note = "主图（完整分辨率）")
                    break
                }
            }
        }

        val mainSource = main?.sourceEntry

        // --- Preview picture: prefer a plain preview.*, then an encoded tex ---
        val isPreviewPath: (String) -> Boolean = { p ->
            p != mainSource && p.contains("preview", ignoreCase = true)
        }

        var preview: ExtractedAsset? = null

        // 1) Plain preview image (e.g. preview.jpg) verbatim.
        val plain = plainImageEntries.firstOrNull { isPreviewPath(it.path) }
        if (plain != null) {
            val b = PkgReader.readEntryBytes(src, plain)
            preview = ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(usedNames, plain.nameWithoutExtension, plain.extension.removePrefix(".")),
                plain.path,
                bytes = b,
                note = "预览图"
            )
        }

        // 2) Preview tex carrying an already-encoded payload (JPEG/PNG).
        if (preview == null) {
            for (c in imageTexCandidates) {
                if (!isPreviewPath(c.path)) continue
                val entry = entryFor(c.path)
                val asset = decodeImage(src, entry, isMobile, lz4) ?: continue
                if (asset.encodedBytes != null) {
                    preview = ExtractedAsset(
                        AssetKind.IMAGE,
                        uniqueName(usedNames, entry.nameWithoutExtension, asset.extension),
                        entry.path,
                        bytes = asset.encodedBytes,
                        note = "预览图"
                    )
                    break
                }
            }
        }

        // 3) Preview tex with raw pixels -> encode on the platform side.
        if (preview == null) {
            for (c in imageTexCandidates) {
                if (!isPreviewPath(c.path)) continue
                val entry = entryFor(c.path)
                val asset = decodeImage(src, entry, isMobile, lz4) ?: continue
                if (asset.rgba != null) {
                    preview = ExtractedAsset(
                        AssetKind.IMAGE,
                        uniqueName(usedNames, entry.nameWithoutExtension, "png"),
                        entry.path,
                        pixelsRgba = asset.rgba,
                        pixelWidth = asset.width,
                        pixelHeight = asset.height,
                        note = "预览图"
                    )
                    break
                }
            }
        }

        progress?.onProgress(total, total, "完成")
        return WallpaperResult(isVideo, main, preview, isMobile)
    }

    /**
     * Streams the video payload of [main] (a VIDEO asset with [ExtractedAsset.fromPackage])
     * into [out] — chunked copy for direct .mp4 entries, streamed LZ4 decode for .tex.
     * Never loads the whole video into memory.
     *
     * @return bytes written.
     */
    fun extractVideoTo(
        src: SeekableData,
        main: ExtractedAsset,
        out: java.io.OutputStream,
        lz4: Lz4BlockDecompressor? = null,
        isMobile: Boolean = false
    ): Long {
        require(main.kind == AssetKind.VIDEO) { "not a video asset" }
        val entry = archiveEntryOrNull(src, main.sourceEntry)
            ?: throw FormatException("Entry not found: ${main.sourceEntry}")
        return if (entry.extension == ".mp4") {
            src.copyRangeTo(out, entry.dataOffset, entry.length)
        } else {
            // .tex 从 entry 自身的 0 偏移开始解析 → 用子视图
            TexVideoExtractor.extractVideoTo(
                SubSeekableData(src, entry.dataOffset, entry.length),
                lz4, out, isMobile
            )
        }
    }

    private fun archiveEntryOrNull(src: SeekableData, path: String): PkgEntry? =
        PkgReader.read(src).entries.firstOrNull { it.path == path }

    // --- Decoding helpers ---

    /**
     * Peek a .tex header by reading only a small prefix (a TEX header is < 100
     * bytes for the compact layout; a v4 video header adds a short JSON blob).
     * Avoids reading whole video textures (tens of MB) just to see dimensions.
     */
    private fun peekTexHeader(src: SeekableData, entry: PkgEntry, isMobile: Boolean): TexPeek? {
        val prefix = PkgReader.readEntryPrefix(src, entry, PEEK_PREFIX_BYTES)
        // NOTE: a truncated prefix throws EOFException (an IOException, NOT a
        // RuntimeException), so catch Exception here, not RuntimeException.
        val fromPrefix = try {
            TexAssetDecoder.peek(prefix, isMobile)
        } catch (e: Exception) {
            null
        }
        if (fromPrefix != null) return fromPrefix
        // Header longer than the prefix (rare) -> fall back to a full read once.
        if (prefix.size.toLong() >= entry.length) return null
        return try {
            TexAssetDecoder.peek(PkgReader.readEntryBytes(src, entry), isMobile)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeImage(
        src: SeekableData, entry: PkgEntry, isMobile: Boolean, lz4: Lz4BlockDecompressor?
    ): TexAsset.Image? {
        if (entry.extension != ".tex") return null
        val raw = PkgReader.readEntryBytes(src, entry)
        val asset = try {
            TexAssetDecoder.decode(raw, lz4, isMobile)
        } catch (e: RuntimeException) {
            null
        }
        return asset as? TexAsset.Image
    }

    private fun toImageExtracted(
        used: MutableSet<String>,
        entry: PkgEntry,
        asset: TexAsset.Image,
        primary: Boolean,
        note: String
    ): ExtractedAsset {
        return when {
            asset.encodedBytes != null -> ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(used, entry.nameWithoutExtension, asset.extension),
                entry.path,
                bytes = asset.encodedBytes,
                isPrimary = primary,
                note = note
            )
            asset.rgba != null -> ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(used, entry.nameWithoutExtension, "png"),
                entry.path,
                pixelsRgba = asset.rgba,
                pixelWidth = asset.width,
                pixelHeight = asset.height,
                isPrimary = primary,
                note = if (primary) "主图（解码自纹理，PNG 无损）" else "图片"
            )
            asset.compressedBytes != null -> ExtractedAsset(
                AssetKind.IMAGE,
                uniqueName(used, entry.nameWithoutExtension, "png"),
                entry.path,
                compressedBytes = asset.compressedBytes,
                pixelWidth = asset.width,
                pixelHeight = asset.height,
                isPrimary = primary,
                note = if (primary) "主图（ETC2 GPU 解码，PNG 无损）" else "ETC2 纹理"
            )
            else -> error("Image asset has no payload")
        }
    }

    private fun uniqueName(used: MutableSet<String>, base: String, extension: String): String {
        val safeBase = base.ifBlank { "wallpaper" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var name = "$safeBase.$extension"
        var i = 1
        while (!used.add(name)) {
            name = "${safeBase}_$i.$extension"
            i++
        }
        return name
    }
}
