package com.doubao.repkg

import android.content.Context

/**
 * 更新检查的本地状态（SharedPreferences），写法与 [ExportSettings] 保持一致。
 *
 * - [autoCheckEnabled]  自动检查开关，**默认开**（见 [UpdateConfig.AUTO_CHECK_DEFAULT]）
 * - [lastCheckMs]       上次检查时间（失败也会写，充当退避）
 * - [ignoredVersionCode] 用户点过「忽略此版本」的版本号
 * - [snoozeUntilMs]     点「稍后提醒」后的静默截止时间
 */
object UpdateSettings {

    private const val PREFS = "update_settings"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_IGNORED = "ignored_version_code"
    private const val KEY_SNOOZE_UNTIL = "snooze_until_ms"

    fun autoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, UpdateConfig.AUTO_CHECK_DEFAULT)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun lastCheckMs(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheckMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, ms).apply()
    }

    fun ignoredVersionCode(context: Context): Int = prefs(context).getInt(KEY_IGNORED, -1)

    fun setIgnoredVersionCode(context: Context, code: Int) {
        prefs(context).edit().putInt(KEY_IGNORED, code).apply()
    }

    fun snoozeUntilMs(context: Context): Long = prefs(context).getLong(KEY_SNOOZE_UNTIL, 0L)

    fun setSnoozeUntilMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_SNOOZE_UNTIL, ms).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
