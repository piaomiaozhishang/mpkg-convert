package com.doubao.repkg

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Extracts a cover frame from an exported MP4 using the platform
 * [MediaMetadataRetriever] (no third-party dependency, no re-encoding).
 *
 * Grabs a frame ~1s in when possible so the cover is not a black/blank first
 * frame, then falls back to the very first sync frame.
 */
object VideoThumbnail {

    private const val ONE_SECOND_US = 1_000_000L

    fun fromUri(context: Context, uri: Uri): Bitmap? = withRetriever { r ->
        r.setDataSource(context, uri)
        frame(r)
    }

    fun fromFile(path: String): Bitmap? = withRetriever { r ->
        r.setDataSource(path)
        frame(r)
    }

    private fun frame(r: MediaMetadataRetriever): Bitmap? =
        r.getFrameAtTime(ONE_SECOND_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    private fun withRetriever(block: (MediaMetadataRetriever) -> Bitmap?): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            block(r)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
