package ai.zcode.remote.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UrlParser 依赖 android.net.Uri 与本地化资源，属于仪器化测试（需连接设备或模拟器运行）：
 * ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class UrlParserTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun parse_extractsNameMidSid() {
        val url = "https://zcode.z.ai/remote/v4?sid=abc123&name=test-device&mid=test-mid-0000&t=1"
        val info = UrlParser.parse(url)

        assertEquals("test-device", info.explicitName)
        assertEquals("test-mid-0000", info.mid)
        assertEquals("abc123", info.sid)
        assertTrue(info.isValidZCodeUrl)
        assertTrue(info.originalUrl.startsWith("https://zcode.z.ai"))
        // 显式 name 优先于本地化兜底
        assertEquals("test-device", info.resolveSuggestedName(context))
    }

    @Test
    fun parse_addsHttpsSchemeWhenMissing() {
        val info = UrlParser.parse("zcode.z.ai/remote/v4?sid=x")
        assertTrue(info.originalUrl.startsWith("https://"))
    }

    @Test
    fun parse_fallsBackToMidPrefix_whenNameMissing() {
        val url = "https://example.com/remote?mid=abcdef1234567890"
        val info = UrlParser.parse(url)
        assertNull(info.explicitName)
        // 建议名走本地化资源：格式串 + mid 前 8 位
        val expected = context.getString(
            ai.zcode.remote.R.string.device_name_with_mid_format, "abcdef12"
        )
        assertEquals(expected, info.resolveSuggestedName(context))
    }

    @Test
    fun parse_marksNonZCodeUrlAsInvalid() {
        val info = UrlParser.parse("https://www.baidu.com/s?wd=hello")
        assertFalse(info.isValidZCodeUrl)
    }

    @Test
    fun parse_handlesBlankInput() {
        val info = UrlParser.parse("")
        assertFalse(info.isValidZCodeUrl)
        // 空 URL 的兜底名也应本地化
        val expected = context.getString(ai.zcode.remote.R.string.default_remote_device)
        assertEquals(expected, info.resolveSuggestedName(context))
    }

    @Test
    fun isDefaultSuggestedName_matchesLocalizedDefaults() {
        val url = "https://example.com/remote?mid=abcdef1234567890"
        val info = UrlParser.parse(url)
        // 空串视为默认
        assertTrue(info.isDefaultSuggestedName(context, ""))
        // 本地化后的兜底名视为默认
        val defaultDevice = context.getString(ai.zcode.remote.R.string.default_zcode_device)
        assertTrue(info.isDefaultSuggestedName(context, defaultDevice))
        // 用户手动改过的名字不被覆盖
        assertFalse(info.isDefaultSuggestedName(context, "我的办公室电脑"))
    }

    @Test
    fun isLikelyZCodeUrl_recognizesClipboardLinks() {
        assertTrue(UrlParser.isLikelyZCodeUrl("https://zcode.z.ai/remote/v4?sid=x"))
        assertTrue(UrlParser.isLikelyZCodeUrl("http://192.168.1.5/remote/v2?sid=y"))
        assertFalse(UrlParser.isLikelyZCodeUrl("https://www.google.com"))
        assertFalse(UrlParser.isLikelyZCodeUrl(null))
    }
}
