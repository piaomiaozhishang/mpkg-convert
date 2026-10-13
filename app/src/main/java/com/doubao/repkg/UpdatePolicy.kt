package com.doubao.repkg

/**
 * 更新检查的**纯判定逻辑**（不碰 Android API、不碰网络，方便单测）。
 *
 * 「查」和「弹」是两件事：
 *  - [shouldCheck]  决定这次要不要去请求 update.json（节流 + 开关 + 防重入）；
 *  - [shouldPrompt] 已经拿到新版本信息后，决定要不要弹窗（忽略版本 / 稍后提醒 / 强制）。
 */
object UpdatePolicy {

    /**
     * @param intervalMs 最小检查间隔，默认 [UpdateConfig.CHECK_INTERVAL_MS]；单测可注入。
     */
    fun shouldCheck(
        nowMs: Long,
        lastCheckMs: Long,
        autoEnabled: Boolean,
        checking: Boolean,
        intervalMs: Long = UpdateConfig.CHECK_INTERVAL_MS
    ): Boolean = autoEnabled && !checking && (nowMs - lastCheckMs) >= intervalMs

    /**
     * 强制更新会同时绕过「忽略此版本」和「稍后提醒」。
     */
    fun shouldPrompt(
        info: UpdateInfo,
        ignoredVersionCode: Int,
        snoozeUntilMs: Long,
        nowMs: Long
    ): Boolean = info.force ||
            (info.versionCode != ignoredVersionCode && nowMs >= snoozeUntilMs)
}
