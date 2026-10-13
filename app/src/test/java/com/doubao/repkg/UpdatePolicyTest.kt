package com.doubao.repkg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「查」与「弹」的判定逻辑，全部参数注入，无需 Android / 网络。 */
class UpdatePolicyTest {

    private fun info(code: Int, force: Boolean = false) = UpdateInfo(
        versionCode = code,
        versionName = "9.9.9",
        title = "t",
        notes = emptyList(),
        force = force,
        downloadUrl = "https://example.com/app.apk"
    )

    // ---- shouldCheck ----

    @Test
    fun blocksWhenAutoCheckDisabled() {
        assertFalse(
            UpdatePolicy.shouldCheck(
                nowMs = 100_000, lastCheckMs = 0,
                autoEnabled = false, checking = false, intervalMs = 1_000
            )
        )
    }

    @Test
    fun blocksWhenAlreadyChecking() {
        assertFalse(
            UpdatePolicy.shouldCheck(
                nowMs = 100_000, lastCheckMs = 0,
                autoEnabled = true, checking = true, intervalMs = 1_000
            )
        )
    }

    @Test
    fun throttlesInsideTheInterval() {
        assertFalse(
            UpdatePolicy.shouldCheck(
                nowMs = 1_000 + 999, lastCheckMs = 1_000,
                autoEnabled = true, checking = false, intervalMs = 1_000
            )
        )
    }

    @Test
    fun allowsAtTheIntervalBoundary() {
        assertTrue(
            UpdatePolicy.shouldCheck(
                nowMs = 1_000 + 1_000, lastCheckMs = 1_000,
                autoEnabled = true, checking = false, intervalMs = 1_000
            )
        )
    }

    // ---- shouldPrompt ----

    @Test
    fun promptsForANewNonIgnoredVersion() {
        assertTrue(UpdatePolicy.shouldPrompt(info(8), -1, 0L, 0L))
    }

    @Test
    fun ignoredVersionStaysSilent() {
        assertFalse(UpdatePolicy.shouldPrompt(info(8), ignoredVersionCode = 8, snoozeUntilMs = 0L, nowMs = 0L))
    }

    @Test
    fun snoozedStaysSilentUntilTheDeadline() {
        assertFalse(UpdatePolicy.shouldPrompt(info(8), -1, snoozeUntilMs = 1_000L, nowMs = 999L))
        assertTrue(UpdatePolicy.shouldPrompt(info(8), -1, snoozeUntilMs = 1_000L, nowMs = 1_000L))
    }

    @Test
    fun forceBypassesIgnoreAndSnooze() {
        assertTrue(
            UpdatePolicy.shouldPrompt(
                info(8, force = true),
                ignoredVersionCode = 8,
                snoozeUntilMs = Long.MAX_VALUE,
                nowMs = 0L
            )
        )
    }
}
