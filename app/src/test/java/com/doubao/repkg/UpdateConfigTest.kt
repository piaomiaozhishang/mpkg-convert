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
    fun updateJsonUrlMustBeRawNotBlobPage() {
        // Gitee 的浏览页地址(/blob/) 返回网页/405，App 解析必失败；必须是 raw 直链。
        for (url in UpdateConfig.UPDATE_JSON_URLS) {
            assertTrue("更新清单必须是 raw 直链，不能用 /blob/ 浏览页：$url", url.contains("/raw/"))
            assertFalse("更新清单不能是 /blob/ 浏览页：$url", url.contains("/blob/"))
        }
    }

    @Test
    fun giteeUrlUsesTheNewAccountSpelling() {
        // 现在用的 Gitee 账户是 piaomiaozhishang（与 GitHub 同拼写）
        assertTrue(
            "应使用新账户 piaomiaozhishang",
            UpdateConfig.UPDATE_JSON_URLS.any { it.contains("piaomiaozhishang") }
        )
    }

    @Test
    fun giteeUrlMustNotUseTheOldAccount() {
        // Gitee 上还有一个拼写相近的旧账户 piaomiaozhisahng（zhiSAHng），已弃用。
        // 曾经因为搞混这两个账户配错地址，这里钉死，防止再犯。
        for (url in UpdateConfig.UPDATE_JSON_URLS) {
            assertFalse("不能用已弃用的旧账户 piaomiaozhisahng：$url", url.contains("piaomiaozhisahng"))
        }
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
