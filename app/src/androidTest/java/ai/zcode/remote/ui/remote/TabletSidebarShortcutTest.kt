package ai.zcode.remote.ui.remote

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.zcode.remote.ui.remote.web.WorkspaceSidebarButtonScript
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
    fun pageButtonTogglesSidebarAndCanBeRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, 800, 600)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                "https://example.test/",
                """
                    <div data-workspace-shell="true">
                      <div data-workspace-sidebar-panel="true" style="width:264px">
                        <aside aria-hidden="false"></aside>
                      </div>
                      <div><button data-testid="desktop-top-nav-back">返回</button></div>
                    </div>
                    <script>
                      window.matchMedia = function() { return { matches: true }; };
                      window.shortcutCount = 0;
                      window.addEventListener('keydown', function(event) {
                        if (event.ctrlKey && event.key === 'b') {
                          event.preventDefault();
                          window.shortcutCount++;
                          document.querySelector('aside').setAttribute('aria-hidden', 'true');
                          document.querySelector('[data-workspace-sidebar-panel]').style.width = '0px';
                        }
                      }, true);
                    </script>
                """.trimIndent(),
                "text/html", "UTF-8", null,
            )
        }
        try {
            assertTrue("测试页加载超时", loaded.await(10, TimeUnit.SECONDS))
            assertEquals("\"open\"", evaluate(webView, WorkspaceSidebarButtonScript.probe(true, "收起侧栏", "展开侧栏")))
            assertEquals("true", evaluate(webView, "!!document.getElementById('zcode-remote-sidebar-toggle')"))
            evaluate(webView, "document.getElementById('zcode-remote-sidebar-toggle').click()")
            assertEquals("1", evaluate(webView, "window.shortcutCount"))
            assertEquals("\"closed\"", evaluate(webView, WorkspaceSidebarButtonScript.probe(true, "收起侧栏", "展开侧栏")))
            assertEquals("\"展开侧栏\"", evaluate(webView, "document.getElementById('zcode-remote-sidebar-toggle').getAttribute('aria-label')"))
            evaluate(webView, WorkspaceSidebarButtonScript.remove)
            assertEquals("false", evaluate(webView, "!!document.getElementById('zcode-remote-sidebar-toggle')"))
            assertEquals("false", evaluate(webView, "!!document.querySelector('[data-zcode-sidebar-nav-shifted]')"))
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observed = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                value = it
                observed.countDown()
            }
        }
        assertTrue("网页脚本回调超时", observed.await(10, TimeUnit.SECONDS))
        return value
    }

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
