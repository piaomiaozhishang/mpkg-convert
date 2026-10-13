package com.doubao.repkg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新配置本身的守卫：地址要合法、优先级要正确、占位符要换掉。 */
class UpdateConfigTest {

    @Test
    fun jsonUrlsAreNotEmptyAndAllHttps() {
        assertTrue("更新地址列表不能为空", UpdateConfig.UPDATE_JSON_URLS.isNotEmpty())
        assertTrue(
            "所有地址都必须是 https",
            UpdateConfig.UPDATE_JSON_URLS.all { it.startsWith("https://") }
        )
    }

    @Test
    fun giteeComesFirstForMainlandUsers() {
        val first = UpdateConfig.UPDATE_JSON_URLS.first()
        assertTrue("国内优先，第一个应是 Gitee，实际是 $first", first.contains("gitee.com"))
    }

    @Test
    fun hasAGithubFallback() {
        assertTrue(
            "应保留 GitHub raw 作为兜底",
            UpdateConfig.UPDATE_JSON_URLS.any { it.contains("raw.githubusercontent.com") }
        )
    }

    @Test
    fun giteeUrlKeepsTheOwnersRealSpelling() {
        // 用户在 Gitee 的用户名是 piaomiaozhisahng（与 GitHub 拼写不同），别被"纠正"
        assertTrue(
            UpdateConfig.UPDATE_JSON_URLS.any { it.contains("piaomiaozhisahng") }
        )
    }

    @Test
    fun isConfiguredWithRealUrls() {
        assertTrue("地址已填真实值，不应被判为未配置", UpdateConfig.isConfigured())
    }

    @Test
    fun repoUrlIsHttps() {
        assertTrue(UpdateConfig.REPO_URL.startsWith("https://"))
        assertFalse(UpdateConfig.REPO_URL.contains("YOUR_NAME"))
    }

    @Test
    fun intervalsAreSane() {
        assertTrue(UpdateConfig.CHECK_INTERVAL_MS > 0)
        assertTrue(UpdateConfig.SNOOZE_MS >= UpdateConfig.CHECK_INTERVAL_MS)
    }
}
