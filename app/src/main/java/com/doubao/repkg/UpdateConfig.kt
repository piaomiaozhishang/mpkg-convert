package com.doubao.repkg

/**
 * 更新与开源信息 —— **只改这一个文件**即可。
 *
 * 两个关键地址：
 *   1. [REPO_URL]        开源仓库地址（设置 → 关于 里显示，可点击打开）
 *   2. [UPDATE_JSON_URLS] 更新清单地址
 *
 * ⚠️ 更新清单必须用 **raw 直链**，不能用仓库浏览页地址：
 *    - ✅ https://gitee.com/<用户>/<仓库>/raw/<分支>/update.json   返回纯文本 JSON
 *    - ❌ https://gitee.com/<用户>/<仓库>/blob/<分支>/update.json  返回网页/405，App 读不了
 *
 * 「下载地址」不在这里，而在 update.json 的 `downloadUrl` 里 ——
 * 所以**换下载源（网盘/Release/对象存储）不需要重新发版**，改 update.json 即可。
 */
object UpdateConfig {

    /** 开源仓库地址（关于页显示，点击打开）。 */
    const val REPO_URL = "https://github.com/piaomiaozhishang/mpkg-convert"

    /**
     * 更新清单地址。目前只保留一个，想加备用地址直接往列表里追加即可。
     *
     * Gitee 用户名是 `piaomiaozhishang`（与 GitHub 同拼写）。
     * 注意 Gitee 上另有一个拼写相近的账户 `piaomiaozhisahng`（zhiSAHng，旧账户，已不用），
     * 里面也有同名 mpkg-convert 仓库 —— **别用那个**。
     */
    val UPDATE_JSON_URLS: List<String> = listOf(
        "https://gitee.com/piaomiaozhishang/mpkg-convert/raw/master/update.json"
    )

    /** 自动检查的最小间隔（默认 12 小时，避免每次启动都打网络）。 */
    const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** 点「稍后提醒」后的静默时长（默认 24 小时）。 */
    const val SNOOZE_MS = 24 * 60 * 60 * 1000L

    /** 自动检查的**默认值**（用户可在「设置 → 检查更新」里关掉）。 */
    const val AUTO_CHECK_DEFAULT = true

    /** 配置是否已填（占位符没换掉时，手动检查会给一句友好提示，而不是报网络错）。 */
    fun isConfigured(): Boolean =
        UPDATE_JSON_URLS.isNotEmpty() &&
                UPDATE_JSON_URLS.none { it.contains("YOUR_NAME") } &&
                UPDATE_JSON_URLS.all { it.startsWith("https://") }
}
