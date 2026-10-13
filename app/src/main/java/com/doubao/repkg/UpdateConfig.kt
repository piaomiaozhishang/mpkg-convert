package com.doubao.repkg

/**
 * 更新与开源信息 —— **只改这一个文件**即可。
 *
 * 上线前把下面两个地址换成你自己的：
 *   1. [REPO_URL]        开源仓库地址（显示在「设置 → 关于」里，可点击打开）
 *   2. [UPDATE_JSON_URL] update.json 直链（默认自动检查会去拉它）
 *
 * update.json 的内容示例见仓库根目录的 `update.json`。
 * 「下载地址」由 update.json 里的 `downloadUrl` 决定，可以是：
 *   - GitHub Release 资产直链，例如
 *     https://github.com/you/Mpkg2Mp4/releases/download/v1.7.0/app-v1.7.0.apk
 *   - 或你自己的网盘/对象存储打包地址
 */
object UpdateConfig {

    /** 开源仓库地址（关于页显示，点击打开）。 */
    const val REPO_URL = "https://github.com/piaomiaozhishang/Mpkg2Mp4"

    /** update.json 的 HTTPS 直链。 */
    const val UPDATE_JSON_URL =
        "https://raw.githubusercontent.com/piaomiaozhishang/Mpkg2Mp4/main/update.json"

    /** 自动检查的最小间隔（默认 12 小时，避免每次启动都打网络）。 */
    const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** 点「稍后提醒」后的静默时长（默认 24 小时）。 */
    const val SNOOZE_MS = 24 * 60 * 60 * 1000L

    /** 自动检查的**默认值**（用户可在「设置 → 检查更新」里关掉）。 */
    const val AUTO_CHECK_DEFAULT = true

    /** 配置是否已填（未填时手动检查会给一句友好提示，而不是报网络错）。 */
    fun isConfigured(): Boolean =
        !UPDATE_JSON_URL.contains("YOUR_NAME") && UPDATE_JSON_URL.startsWith("https://")
}
