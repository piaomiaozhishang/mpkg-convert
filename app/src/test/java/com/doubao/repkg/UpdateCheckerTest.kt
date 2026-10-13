package com.doubao.repkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * update.json 的解析与版本判定。
 * 依赖 `testImplementation(libs.org.json)`（android.jar 里的 org.json 是会抛
 * "not mocked" 的桩，单测必须用真实现）。
 */
class UpdateCheckerTest {

    private val base = """
        {
          "latestVersionCode": 8,
          "latestVersionName": "1.7.0",
          "minSupportedVersionCode": 0,
          "force": false,
          "title": "v1.7.0 更新",
          "notes": ["修复导出颠倒", "历史记录更流畅"],
          "downloadUrl": "https://example.com/app-v1.7.0.apk",
          "sizeBytes": 16673216,
          "sha256": "cd8b8f67"
        }
    """.trimIndent()

    @Test
    fun newerVersionIsDetected() {
        val info = UpdateChecker.parse(base, localVersionCode = 7)
        assertNotNull(info)
        assertEquals(8, info!!.versionCode)
        assertEquals("1.7.0", info.versionName)
        assertEquals(listOf("修复导出颠倒", "历史记录更流畅"), info.notes)
        assertFalse(info.force)
        assertEquals("https://example.com/app-v1.7.0.apk", info.downloadUrl)
        assertEquals(16_673_216L, info.sizeBytes)
        assertEquals("cd8b8f67", info.sha256)
    }

    @Test
    fun sameVersionReturnsNull() {
        assertNull(UpdateChecker.parse(base, localVersionCode = 8))
    }

    @Test
    fun olderLocalVersionAlsoReturnsNullWhenRemoteIsNotNewer() {
        assertNull(UpdateChecker.parse(base, localVersionCode = 9))
    }

    @Test
    fun explicitForceFlagIsHonoured() {
        val json = base.replace("\"force\": false", "\"force\": true")
        assertTrue(UpdateChecker.parse(json, 7)!!.force)
    }

    @Test
    fun minSupportedVersionCodeForcesUpdate() {
        // 本地 7 低于最低支持 8 -> 即使 force=false 也强制
        val json = base.replace("\"minSupportedVersionCode\": 0", "\"minSupportedVersionCode\": 8")
        val info = UpdateChecker.parse(json, 7)
        assertNotNull(info)
        assertTrue(info!!.force)
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(Exception::class.java) { UpdateChecker.parse("{ not json", 1) }
    }

    @Test
    fun missingLatestVersionCodeYieldsNoUpdate() {
        assertNull(UpdateChecker.parse("{}", localVersionCode = 1))
    }

    @Test
    fun missingOptionalFieldsDoNotCrash() {
        val info = UpdateChecker.parse("""{"latestVersionCode": 5}""", localVersionCode = 1)
        assertNotNull(info)
        assertEquals("", info!!.versionName)
        assertEquals(emptyList<String>(), info.notes)
        assertFalse(info.force)
        assertNull(info.sizeBytes)
        assertNull(info.sha256)
    }

    @Test
    fun checkOnAnInvalidUrlReportsFailureInsteadOfCrashing() {
        // 需要一个确定性、离线的用例：非法 URL 一定失败，但绝不能抛异常。
        val result = UpdateChecker.check("not a valid url", localVersionCode = 1)
        assertTrue("expected Failed, got $result", result is UpdateChecker.CheckResult.Failed)
    }
}
