package ai.zcode.remote.ui.remote

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 验证原生组合键能到达 WebView 网页的捕获阶段监听器。 */
@RunWith(AndroidJUnit4::class)
class TabletSidebarShortcutTest {
    @Test
    fun ctrlBReachesPageShortcut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                "https://example.test/",
                """
                    <script>
                    window.shortcutCount = 0;
                    window.addEventListener('keydown', function(event) {
                      if (event.ctrlKey && event.key.toLowerCase() === 'b') {
                        event.preventDefault();
                        window.shortcutCount++;
                      }
                    }, true);
                    </script>
                """.trimIndent(),
                "text/html", "UTF-8", null,
            )
        }
        try {
            assertTrue("测试页加载超时", loaded.await(10, TimeUnit.SECONDS))
            val observed = CountDownLatch(1)
            var count = ""
            instrumentation.runOnMainSync {
                val now = SystemClock.uptimeMillis()
                webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON))
                webView.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON))
                webView.evaluateJavascript("window.shortcutCount") {
                    count = it
                    observed.countDown()
                }
            }
            assertTrue("网页脚本回调超时", observed.await(10, TimeUnit.SECONDS))
            assertEquals("1", count)
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }
}
