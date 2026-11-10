package io.github.qianyeeew.dshremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号（m3-release.md T2）：versionName 来自 gradle.properties 的 dshremote.version，
 * versionCode 按约定的公式由它算出。这里用独立的实现再算一遍，防止构建脚本里的公式被改错。
 * 单元测试只在 debug 变体上跑（AGP 9 默认）；release 包的包名和版本由发布流程用 aapt2 检查。
 */
class VersionTest {
    @Test
    fun versionCodeFollowsVersionName() {
        val name = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        assertEquals(expectedCode(name), BuildConfig.VERSION_CODE)
    }

    @Test
    fun debugBuildIsMarked() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-debug"))
        assertEquals("io.github.qianyeeew.dshremote.debug", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun formulaOrdersReleases() {
        val ordered = listOf("0.1.0-rc.1", "0.1.0-rc.2", "0.1.0", "0.1.1-rc.1", "0.1.1", "0.2.0", "1.0.0")
        val codes = ordered.map(::expectedCode)
        assertEquals(codes.sorted(), codes)
        assertEquals(10_099, expectedCode("0.1.0"))
        assertEquals(10_001, expectedCode("0.1.0-rc.1"))
    }

    private fun expectedCode(name: String): Int {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(name)
        assertNotNull("versionName 格式不对：$name", match)
        val (x, y, z, rc) = match!!.destructured
        return x.toInt() * 1_000_000 + y.toInt() * 10_000 + z.toInt() * 100 + (rc.toIntOrNull() ?: 99)
    }
}
