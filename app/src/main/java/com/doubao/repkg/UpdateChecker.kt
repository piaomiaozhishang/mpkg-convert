package com.doubao.repkg

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拉取并解析 update.json（零第三方依赖：`HttpURLConnection` + `org.json`）。
 *
 * 隐私：只做一次 GET，**不带任何查询参数、不带设备标识、不上传任何数据**。
 */
object UpdateChecker {

    private const val TIMEOUT_MS = 5_000

    /** 一次检查的结果。把「没有新版本」和「网络失败」分开，手动检查才能给出准确提示。 */
    sealed interface CheckResult {
        data class NewVersion(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * 解析 update.json。
     *
     * @return 有新版本时返回 [UpdateInfo]；已是最新返回 `null`。
     * @throws org.json.JSONException 内容不是合法 JSON / 结构不对时抛出（调用方自行兜底）。
     */
    fun parse(json: String, localVersionCode: Int): UpdateInfo? {
        val o = JSONObject(json)
        val latest = o.optInt("latestVersionCode", 0)
        if (latest <= 0 || latest <= localVersionCode) return null   // 已是最新

        val minSupported = o.optInt("minSupportedVersionCode", 0)
        val notes = o.optJSONArray("notes")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            .orEmpty()

        return UpdateInfo(
            versionCode = latest,
            versionName = o.optString("latestVersionName", ""),
            title = o.optString("title", "发现新版本"),
            notes = notes,
            // force=true，或本地版本已经低于「最低支持版本」——后者是兜底强更
            force = o.optBoolean("force", false) || (minSupported > 0 && localVersionCode < minSupported),
            downloadUrl = o.optString("downloadUrl", ""),
            sizeBytes = o.optLong("sizeBytes", 0L).takeIf { it > 0 },
            sha256 = o.optString("sha256", "").takeIf { it.isNotBlank() }
        )
    }

    /**
     * 请求 [url] 并解析。**不抛异常**：网络/解析问题都收敛成 [CheckResult.Failed]。
     */
    fun check(url: String, localVersionCode: Int): CheckResult = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                CheckResult.Failed("HTTP $code")
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val info = parse(text, localVersionCode)
                if (info == null) CheckResult.UpToDate else CheckResult.NewVersion(info)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        CheckResult.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }
}
