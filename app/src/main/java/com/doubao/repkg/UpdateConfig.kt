package com.doubao.repkg

/**
 * 更新与开源信息 —— **只改这一个文件**即可。
 *
 * 两个关键地址：
 *   1. [REPO_URL]         开源仓库地址（设置 → 关于 里显示，可点击打开）= GitHub
 *   2. [UPDATE_JSON_URLS] 更新清单地址列表，**按顺序尝试，第一个拿不到就试下一个**
 *      - 主用 **Gitee**：国内手机直接可访问、免登录
 *      - 兜底 **GitHub raw**：海外或有代理时可用
 *      Gitee 的默认分支可能是 master 也可能是 main，两个都写上更保险。
 *
 * 「下载地址」不在这里，而在 update.json 的 `downloadUrl` 里 ——
 * 所以**换下载源（网盘/Release/对象存储）不需要重新发版**，改 update.json 即可。
 */
object UpdateConfig {

    /** 开源仓库地址（关于页显示，点击打开）。 */
    const val REPO_URL = "https://github.com/piaomiaozhishang/mpkg-convert"

    /**
     * 更新清单地址（按优先级排列，依次尝试）。
     * 注意 `piaomiaozhisahng` 是用户在 **Gitee** 的真实用户名，与 GitHub 的
     * `piaomiaozhishang` 拼写不同（少了一个 h 的位置），别"顺手改对"。
     */
    val UPDATE_JSON_URLS: List<String> = listOf(
        "https://gitee.com/piaomiaozhisahng/mpkg-convert/raw/master/update.json",
        "https://gitee.com/piaomiaozhisahng/mpkg-convert/raw/main/update.json",
        "https://raw.githubusercontent.com/piaomiaozhishang/mpkg-convert/main/update.json"
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
