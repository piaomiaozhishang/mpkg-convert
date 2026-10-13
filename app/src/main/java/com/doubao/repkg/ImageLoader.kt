package com.doubao.repkg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal async image loader for the history list, the result card and the
 * full-screen viewer.
 *
 * Why it exists: the previous code called `BitmapFactory.decodeFile` directly
 * inside composables, i.e. on the main thread, once per list row while scrolling
 * and once for the full-resolution image. That is the cause of the history
 * screen jank. This loader:
 *   - decodes on [Dispatchers.IO];
 *   - downsamples to the size actually needed (`inSampleSize`);
 *   - keeps a bounded in-memory LRU cache so re-scrolling is instant.
 *
 * Zero third-party dependencies (unlike Coil/Glide), matching the app's
 * "no network, minimal deps" philosophy.
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun cacheBytes(): Int {
        val eighth = (Runtime.getRuntime().maxMemory() / 8).toInt()
        return eighth.coerceIn(8 * 1024 * 1024, 64 * 1024 * 1024)
    }

    private fun key(id: String, maxEdge: Int) = "$maxEdge|$id"

    // ---- synchronous decoders (call from IO only) ----

    fun decodeFile(path: String, maxEdge: Int): Bitmap? =
        sampled(maxEdge) { opts -> BitmapFactory.decodeFile(path, opts) }

    fun decodeBytes(bytes: ByteArray, maxEdge: Int): Bitmap? =
        sampled(maxEdge) { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }

    fun decodeUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        sampled(maxEdge) { opts ->
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }

    /**
     * Two-pass decode: read the bounds, compute [BitmapSampling.inSampleSize],
     * then decode for real. [open] is expected to be re-invokable (streams are
     * reopened, files are re-read).
     */
    private fun sampled(maxEdge: Int, open: (BitmapFactory.Options) -> Bitmap?): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = BitmapSampling.inSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            open(opts)
        }
    } catch (e: Throwable) {
        null
    }

    // ---- Compose helpers ----

    /** File-path bitmap, decoded off the main thread and cached. */
    @Composable
    fun rememberFileBitmap(path: String?, maxEdge: Int): Bitmap? {
        if (path == null) return null
        val k = key(path, maxEdge)
        val state = produceState<Bitmap?>(initialValue = cache.get(k), key1 = k) {
            if (value == null) {
                val bmp = withContext(Dispatchers.IO) { decodeFile(path, maxEdge) }
                if (bmp != null) cache.put(k, bmp)
                value = bmp
            }
        }
        return state.value
    }

    /** Content-URI bitmap (used by the full-screen viewer), decoded off the main thread. */
    @Composable
    fun rememberUriBitmap(context: Context, uri: Uri?, maxEdge: Int): Bitmap? {
        if (uri == null) return null
        val k = key(uri.toString(), maxEdge)
        val state = produceState<Bitmap?>(initialValue = cache.get(k), key1 = k) {
            if (value == null) {
                val bmp = withContext(Dispatchers.IO) { decodeUri(context, uri, maxEdge) }
                if (bmp != null) cache.put(k, bmp)
                value = bmp
            }
        }
        return state.value
    }
}
