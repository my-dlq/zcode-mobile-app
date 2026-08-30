package ai.zcode.remote.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UrlParser 依赖 android.net.Uri，属于仪器化测试（需连接设备或模拟器运行）：
 * ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class UrlParserTest {

    @Test
    fun parse_extractsNameMidSid() {
        val url = "https://zcode.z.ai/remote/v4?sid=abc123&name=test-device&mid=test-mid-0000&t=1"
        val info = UrlParser.parse(url)

        assertEquals("test-device", info.suggestedName)
        assertEquals("test-mid-0000", info.mid)
        assertEquals("abc123", info.sid)
        assertTrue(info.isValidZCodeUrl)
        assertTrue(info.originalUrl.startsWith("https://zcode.z.ai"))
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
        // mid 前 8 位作为设备名
        assertEquals("设备 abcdef12", info.suggestedName)
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
    }

    @Test
    fun isLikelyZCodeUrl_recognizesClipboardLinks() {
        assertTrue(UrlParser.isLikelyZCodeUrl("https://zcode.z.ai/remote/v4?sid=x"))
        assertTrue(UrlParser.isLikelyZCodeUrl("http://192.168.1.5/remote/v2?sid=y"))
        assertFalse(UrlParser.isLikelyZCodeUrl("https://www.google.com"))
        assertFalse(UrlParser.isLikelyZCodeUrl(null))
    }
}
