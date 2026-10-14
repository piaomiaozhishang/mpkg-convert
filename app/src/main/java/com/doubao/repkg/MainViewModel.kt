package com.doubao.repkg

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doubao.repkg.core.AssetKind
import com.doubao.repkg.core.ChannelSeekableData
import com.doubao.repkg.core.Etc2CpuDecoder
import com.doubao.repkg.core.ExtractedAsset
import com.doubao.repkg.core.Extractor
import com.doubao.repkg.core.SeekableData
import com.doubao.repkg.core.WallpaperResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Longest edge kept for the preview card bitmap. */
private const val CARD_EDGE = 1080

/** Minimum gap between progress updates pushed to the UI (avoids recompose storms). */
private const val PROGRESS_MIN_INTERVAL_MS = 100L

sealed interface UiState {
    data object Idle : UiState
    data class Working(val current: Int, val total: Int, val message: String) : UiState

    /** Wallpaper analyzed; the user picks which piece (main / preview) to export. */
    data class Ready(
        val isVideo: Boolean,
        val hasMain: Boolean,
        val hasPreview: Boolean,
        /** Local path of the card's preview picture, if any. */
        val cardImagePath: String?,
        val mainLabel: String,
        val previewLabel: String
    ) : UiState

    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _history = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val history: StateFlow<List<HistoryRecord>> = _history.asStateFlow()

    private var result: WallpaperResult? = null

    /** Caches expensive resolved bytes (GPU/CPU-decoded ETC2) between card and export. */
    private val resolvedCache = HashMap<String, ByteArray>()

    // --- 大文件流式支持：打开的数据源与视频临时文件 ---
    private var openResource: java.io.Closeable? = null
    private var src: SeekableData? = null
    private var analyzedIsMobile: Boolean = false
    private var videoTemp: File? = null
    private var cacheTemp: File? = null

    private fun closeSources() {
        runCatching { openResource?.close() }
        openResource = null
        src = null
        runCatching { videoTemp?.delete() }
        videoTemp = null
        runCatching { cacheTemp?.delete() }
        cacheTemp = null
    }

    override fun onCleared() {
        closeSources()
        super.onCleared()
    }

    // --- 更新检查的状态（必须声明在 init 之前：init 里会写 _lastCheckMs）---

    private val _updatePrompt = MutableStateFlow<UpdateInfo?>(null)

    /** 非空时弹「发现新版本」对话框。 */
    val updatePrompt: StateFlow<UpdateInfo?> = _updatePrompt.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    private val _lastCheckMs = MutableStateFlow(0L)
    val lastCheckMs: StateFlow<Long> = _lastCheckMs.asStateFlow()

    private var updateCheckRunning = false

    init {
        refreshHistory()
        _lastCheckMs.value = UpdateSettings.lastCheckMs(getApplication())
    }

    fun reset() {
        _state.value = UiState.Idle
        result = null
        resolvedCache.clear()
        closeSources()
    }

    fun refreshHistory() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _history.value = withContext(Dispatchers.IO) { HistoryStore.getAll(context) }
        }
    }

    fun deleteRecord(id: Long) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { HistoryStore.delete(context, id) }
            refreshHistory()
        }
    }

    fun clearHistory() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { HistoryStore.clear(context) }
            refreshHistory()
        }
    }

    // --- 更新检查（状态字段声明在类的顶部，见 init 之前）---

    /** 冷启动时调一次；受 12h 节流 + 自动开关 + 防重入保护，失败静默。 */
    fun maybeCheckForUpdate() {
        val context = getApplication<Application>()
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(
                now, UpdateSettings.lastCheckMs(context),
                UpdateSettings.autoCheckEnabled(context), updateCheckRunning
            )
        ) return
        runUpdateCheck(context, now, manual = false)
    }

    /** 设置页「检查更新」手动触发：不受节流限制，且**必定弹出**（用户是主动来查的）。 */
    fun checkForUpdateNow() {
        val context = getApplication<Application>()
        runUpdateCheck(context, System.currentTimeMillis(), manual = true)
    }

    private fun runUpdateCheck(context: Context, nowMs: Long, manual: Boolean) {
        if (updateCheckRunning) return
        if (!UpdateConfig.isConfigured()) {
            if (manual) viewModelScope.launch { _events.emit("更新地址还没配置（见 UpdateConfig.kt）") }
            return
        }
        updateCheckRunning = true
        _checkingUpdate.value = true
        viewModelScope.launch {
            try {
                val local = localVersionCode(context)
                val result = withContext(Dispatchers.IO) {
                    // 依次尝试配置的地址（目前只有一个 Gitee raw 直链）。
                    var last: UpdateChecker.CheckResult =
                        UpdateChecker.CheckResult.Failed("没有可用的更新地址")
                    for (url in UpdateConfig.UPDATE_JSON_URLS) {
                        val r = UpdateChecker.check(url, local)
                        if (r !is UpdateChecker.CheckResult.Failed) return@withContext r
                        last = r
                    }
                    last
                }
                // 失败也写时间戳：充当退避，避免离线时每次启动都重试
                UpdateSettings.setLastCheckMs(context, nowMs)
                _lastCheckMs.value = nowMs

                when (result) {
                    is UpdateChecker.CheckResult.UpToDate ->
                        if (manual) _events.emit("已是最新版本（v${appVersionName(context)}）")

                    is UpdateChecker.CheckResult.Failed ->
                        // 自动检查时静默；手动检查才告诉用户为什么
                        if (manual) _events.emit("检查失败：${result.reason}")

                    is UpdateChecker.CheckResult.NewVersion -> {
                        val info = result.info
                        // 手动检查时不受「忽略 / 稍后」限制——用户是主动来查的
                        val prompt = manual || UpdatePolicy.shouldPrompt(
                            info,
                            UpdateSettings.ignoredVersionCode(context),
                            UpdateSettings.snoozeUntilMs(context),
                            nowMs
                        )
                        if (prompt) {
                            _updatePrompt.value = info
                            if (manual) _events.emit("发现新版本 v${info.versionName}")
                        }
                    }
                }
            } finally {
                _checkingUpdate.value = false
                updateCheckRunning = false
            }
        }
    }

    /** 立即更新：打开下载地址（GitHub Release 资产 / 网盘链接均可）。 */
    fun onUpdateNow(info: UpdateInfo) {
        val context = getApplication<Application>()
        _updatePrompt.value = null
        if (info.downloadUrl.isBlank()) {
            viewModelScope.launch { _events.emit("下载地址为空，请检查 update.json") }
            return
        }
        val ok = try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
        if (!ok) viewModelScope.launch { _events.emit("无法打开下载链接") }
    }

    /** 稍后提醒：静默 24 小时。 */
    fun onUpdateLater(info: UpdateInfo) {
        val context = getApplication<Application>()
        UpdateSettings.setSnoozeUntilMs(context, System.currentTimeMillis() + UpdateConfig.SNOOZE_MS)
        _updatePrompt.value = null
    }

    /** 忽略此版本：该版本号以后不再弹。 */
    fun onUpdateIgnore(info: UpdateInfo) {
        val context = getApplication<Application>()
        UpdateSettings.setIgnoredVersionCode(context, info.versionCode)
        _updatePrompt.value = null
    }

    private fun localVersionCode(context: Context): Int {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun appVersionName(context: Context): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: ""
    }

    // --- Analyze (parse only; nothing is exported yet) ---

    fun process(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _state.value = UiState.Working(0, 0, "正在读取壁纸…")
            closeSources()
            resolvedCache.clear()
            try {
                // 打开一次数据源并保持到本次结果结束（导出时还要从包里流式读取）
                val opened = withContext(Dispatchers.IO) { openSource(context, uri) }
                openResource = opened.first
                src = opened.second
                var lastTick = 0L
                val r = withContext(Dispatchers.IO) {
                    Extractor.analyze(
                        src = opened.second,
                        lz4 = AndroidLz4,
                        progress = { current, total, message ->
                            // Throttle: a package can have many entries; pushing a new
                            // state per entry would recompose the whole screen each time.
                            val now = System.currentTimeMillis()
                            if (current >= total || now - lastTick >= PROGRESS_MIN_INTERVAL_MS) {
                                lastTick = now
                                _state.value = UiState.Working(current, total, message)
                            }
                        }
                    )
                }
                analyzedIsMobile = r.isMobile
                result = r
                if (r.main == null && r.preview == null) {
                    _state.value = UiState.Error(
                        "没有在该文件中找到可导出的视频、原图或预览图。\n" +
                            "纯代码/网页类壁纸不含现成 MP4 或纹理图片。"
                    )
                } else {
                    val card = withContext(Dispatchers.IO) { buildCardImage(context, r) }
                    _state.value = UiState.Ready(
                        isVideo = r.isVideo,
                        hasMain = r.main != null,
                        hasPreview = r.preview != null,
                        cardImagePath = card,
                        mainLabel = mainLabel(r),
                        previewLabel = previewLabel(r)
                    )
                }
            } catch (e: OutOfMemoryError) {
                closeSources()
                _state.value = UiState.Error("内存不足：这个壁纸太大，转换失败。请试试较小的文件。")
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "解析失败")
            }
        }
    }

    // --- Export only the piece the user tapped ---

    fun exportMain() {
        result?.main?.let { export(it) }
    }

    fun exportPreview() {
        result?.preview?.let { export(it) }
    }

    private fun export(asset: ExtractedAsset) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) { doExport(context, asset) }
                _events.emit("已保存：${asset.fileName}")
                refreshHistory()
            } catch (e: OutOfMemoryError) {
                _events.emit("内存不足：文件过大，导出失败")
            } catch (e: Exception) {
                _events.emit("导出失败：${e.message ?: "未知错误"}")
            } finally {
                _busy.value = false
            }
        }
    }

    // --- Internals ---

    /**
     * Opens the package as a seekable data source. Prefers a file descriptor
     * (zero-copy, seekable); falls back to a cache copy for providers that
     * cannot be opened that way.
     */
    private fun openSource(context: Context, uri: Uri): Pair<java.io.Closeable, SeekableData> {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                val resource = java.io.Closeable {
                    runCatching { channel.close() }
                    runCatching { pfd.close() }
                }
                return Pair(resource, ChannelSeekableData(channel))
            }
        } catch (e: Exception) {
            // fall through to the cache-copy path
        }
        val temp = copyToCache(context, uri)
        cacheTemp = temp
        val channel = java.io.FileInputStream(temp).channel
        return Pair(channel, ChannelSeekableData(channel))
    }

    private fun analyze(src: SeekableData): WallpaperResult {
        var lastTick = 0L
        return Extractor.analyze(
            src = src,
            lz4 = AndroidLz4,
            progress = { current, total, message ->
                // Throttle: a package can have many entries; pushing a new
                // state per entry would recompose the whole screen each time.
                val now = System.currentTimeMillis()
                if (current >= total || now - lastTick >= PROGRESS_MIN_INTERVAL_MS) {
                    lastTick = now
                    _state.value = UiState.Working(current, total, message)
                }
            }
        )
    }

    /** 解出视频到缓存临时文件（流式，几 GB 也只占 1 MiB 内存），供封面与导出复用。 */
    @Synchronized
    private fun ensureVideoTemp(main: ExtractedAsset): File? {
        videoTemp?.let { if (it.exists()) return it }
        val srcData = src ?: return null
        val tmp = File(getApplication<Application>().cacheDir, "video_${System.currentTimeMillis()}.mp4")
        return try {
            java.io.FileOutputStream(tmp).use { out ->
                Extractor.extractVideoTo(srcData, main, out, AndroidLz4, analyzedIsMobile)
            }
            if (tmp.length() > 0) {
                videoTemp = tmp
                tmp
            } else {
                tmp.delete()
                null
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            null
        }
    }

    /** Resolves an asset to final file bytes (GPU-decoding ETC2 when needed). */
    @Synchronized
    private fun resolveBytes(asset: ExtractedAsset): ByteArray {
        resolvedCache[asset.sourceEntry]?.let { return it }
        val bytes = when {
            asset.bytes != null -> asset.bytes
            asset.pixelsRgba != null -> AssetSaver.encodePng(
                asset.pixelsRgba, asset.pixelWidth, asset.pixelHeight
            )
            asset.compressedBytes != null -> decodeEtc2ToPng(asset)
            else -> error("该项目没有可导出的内容")
        }
        // Only the compressed decode is expensive enough to be worth caching.
        if (asset.compressedBytes != null) resolvedCache[asset.sourceEntry] = bytes
        return bytes
    }

    /** ETC2_RGBA8 -> PNG. Tries the GPU first, falls back to the verified CPU decoder. */
    private fun decodeEtc2ToPng(asset: ExtractedAsset): ByteArray {
        val w = asset.pixelWidth
        val h = asset.pixelHeight
        val data = asset.compressedBytes!!
        return try {
            Etc2Decoder().use { dec ->
                AssetSaver.encodeBitmapPng(dec.decode(data, w, h))
            }
        } catch (gpuError: Exception) {
            // Emulators / old drivers without a usable GLES3 offscreen context.
            AssetSaver.encodeBitmapPng(decodeEtc2CpuBitmap(data, w, h))
        }
    }

    private fun decodeEtc2CpuBitmap(data: ByteArray, w: Int, h: Int): Bitmap {
        val argb = Etc2CpuDecoder.decodeArgb(data, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(argb, 0, w, 0, 0, w, h)
        }
    }

    private fun doExport(context: Context, asset: ExtractedAsset) {
        // 大视频：流式解到缓存临时文件，再分块拷入媒体库/自选目录（全程不进内存）
        if (asset.kind == AssetKind.VIDEO && asset.fromPackage) {
            val tmp = ensureVideoTemp(asset) ?: error("视频提取失败（可能存储空间不足）")
            val size = tmp.length()
            when (val saveResult = AssetSaver.saveFromFile(context, asset.kind, asset.fileName, tmp)) {
                is AssetSaver.Result.Saved -> {
                    videoTemp?.delete()
                    videoTemp = null
                    val id = System.currentTimeMillis()
                    val thumb = HistoryStore.makeVideoThumb(context, id, saveResult.uri)
                    HistoryStore.add(
                        context,
                        HistoryRecord(
                            id = id,
                            timeMs = System.currentTimeMillis(),
                            kind = asset.kind,
                            fileName = asset.fileName,
                            sizeBytes = size,
                            width = asset.pixelWidth,
                            height = asset.pixelHeight,
                            uriString = saveResult.uri.toString(),
                            location = saveResult.path,
                            thumbPath = thumb
                        )
                    )
                }
                is AssetSaver.Result.Failed -> error(saveResult.reason)
            }
            return
        }

        val finalBytes = resolveBytes(asset)

        val saveResult = AssetSaver.save(context, asset.kind, asset.fileName, finalBytes)
        val openUri: Uri
        when (saveResult) {
            is AssetSaver.Result.Saved -> openUri = saveResult.uri
            is AssetSaver.Result.Failed -> error(saveResult.reason)
        }

        val id = System.currentTimeMillis()
        val thumb = when (asset.kind) {
            AssetKind.IMAGE -> HistoryStore.makeImageThumb(context, id, finalBytes)
            AssetKind.VIDEO -> HistoryStore.makeVideoThumb(context, id, openUri)
        }

        HistoryStore.add(
            context,
            HistoryRecord(
                id = id,
                timeMs = System.currentTimeMillis(),
                kind = asset.kind,
                fileName = asset.fileName,
                sizeBytes = finalBytes.size.toLong(),
                width = asset.pixelWidth,
                height = asset.pixelHeight,
                uriString = openUri.toString(),
                location = (saveResult as AssetSaver.Result.Saved).path,
                thumbPath = thumb
            )
        )
    }

    /**
     * Builds the small preview card shown on the Extract screen.
     *  - dynamic wallpaper: a frame grabbed from the video (so it has a cover);
     *  - static wallpaper : the package preview, else the main image.
     * Always downscaled to [CARD_EDGE] and written as a real JPEG (the previous
     * code wrote full-resolution bytes into a ".jpg" file regardless of type).
     */
    private fun buildCardImage(context: Context, r: WallpaperResult): String? {
        val dir = File(context.cacheDir, "card").apply { mkdirs() }

        val main = r.main
        if (r.isVideo && main != null) {
            // 大视频：流式解出到缓存临时文件，再抽帧（内存占用恒定）
            if (main.fromPackage) {
                val tmp = ensureVideoTemp(main)
                if (tmp != null) videoFrameToJpg(context, tmp, dir)?.let { return it }
            } else if (main.bytes != null) {
                videoFrameToJpg(context, main.bytes, dir)?.let { return it }
            }
        }

        val source = r.preview ?: main?.takeIf { it.kind == AssetKind.IMAGE }
        if (source != null) {
            imageAssetToJpg(source, dir)?.let { return it }
        }
        return null
    }

    private fun videoFrameToJpg(context: Context, videoBytes: ByteArray, dir: File): String? {
        val tmp = File(context.cacheDir, "card_src_${System.currentTimeMillis()}.mp4")
        return try {
            tmp.writeBytes(videoBytes)
            val frame = VideoThumbnail.fromFile(tmp.absolutePath) ?: return null
            writeJpg(dir, "card_video", scaleDown(frame, CARD_EDGE))
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    private fun videoFrameToJpg(context: Context, videoFile: File, dir: File): String? {
        return try {
            val frame = VideoThumbnail.fromFile(videoFile.absolutePath) ?: return null
            writeJpg(dir, "card_video", scaleDown(frame, CARD_EDGE))
        } catch (e: Exception) {
            null
        }
    }

    private fun imageAssetToJpg(asset: ExtractedAsset, dir: File): String? {
        val bmp = try {
            when {
                asset.bytes != null ->
                    ImageLoader.decodeBytes(asset.bytes!!, CARD_EDGE)
                asset.pixelsRgba != null ->
                    scaleDown(rawToBitmap(asset.pixelsRgba!!, asset.pixelWidth, asset.pixelHeight), CARD_EDGE)
                // ETC2: resolve once (cached as PNG) so a later export reuses this decode.
                asset.compressedBytes != null ->
                    ImageLoader.decodeBytes(resolveBytes(asset), CARD_EDGE)
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        return writeJpg(dir, "card_img", bmp)
    }

    private fun rawToBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "bad raw size" }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
        }
    }

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longer = maxOf(src.width, src.height)
        if (longer <= maxEdge) return src
        val scale = maxEdge.toFloat() / longer
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out != src) src.recycle()
        return out
    }

    private fun writeJpg(dir: File, prefix: String, bmp: Bitmap): String? {
        return try {
            val f = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun mainLabel(r: WallpaperResult): String {
        val m = r.main ?: return ""
        return if (m.kind == AssetKind.VIDEO) {
            "导出视频 MP4"
        } else {
            if (m.pixelWidth > 0 && m.pixelHeight > 0)
                "导出原图（${m.pixelWidth}×${m.pixelHeight}）"
            else "导出原图"
        }
    }

    private fun previewLabel(r: WallpaperResult): String {
        val p = r.preview ?: return ""
        return if (p.pixelWidth > 0 && p.pixelHeight > 0)
            "导出预览图（${p.pixelWidth}×${p.pixelHeight}）"
        else "导出预览图"
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val temp = File(context.cacheDir, "input_${System.currentTimeMillis()}.pkg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取所选文件")
        return temp
    }
}
