package ai.zcode.remote.ui.remote.web

import android.net.Uri
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.model.ConnectionLoginSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 使用纯静态页面覆盖真实多窗口回调，不启动连接 Activity 或访问连接仓库。 */
@RunWith(AndroidJUnit4::class)
class AuthorizationWindowTest {
    @Test
    fun popupClosesWhenAuthorizationPageClosesItself() = runAuthorization(closeByPage = true)

    @Test
    fun openerPollingReturnsWithoutAuthorizationPageClosingItself() = runAuthorization(closeByPage = false)

    private fun runAuthorization(closeByPage: Boolean) {
        val space = ConnectionLoginSpace("authorization-window-test-${UUID.randomUUID()}")
        val ownerActive = AtomicBoolean(true)
        val popupReference = AtomicReference<WebView?>()
        val popupLoaded = CountDownLatch(1)
        val openerLoaded = CountDownLatch(1)
        val openerCompleted = CountDownLatch(1)
        val nonHttpNavigationBlocked = CountDownLatch(1)
        val duplicateRejected = AtomicBoolean(false)
        var parent: FrameLayout? = null
        var opener: WebView? = null
        var controller: AuthorizationWindowController? = null

        try {
            onMain {
                val context = ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    R.style.Theme_ZCodeRemote,
                )
                val root = FrameLayout(context)
                val source = WebView(context)
                parent = root
                opener = source
                // 必须先绑定测试空间，再接触设置或导航；不操作默认 Profile 的 Cookie。
                ConnectionWebProfile.bind(source, space)
                source.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)
                }
                root.addView(source, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                measureAndLayout(root)
                val windowController = AuthorizationWindowController(root, space) { ownerActive.get() }
                controller = windowController
                source.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                        fixtureResponse(request?.url)

                    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse =
                        fixtureResponse(url?.let(Uri::parse))

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        // 与 Activity 一样，在发起页导航时清理授权窗口。
                        if (url == COMPLETE_URL) windowController.close()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == OPENER_URL) openerLoaded.countDown()
                        if (url == COMPLETE_URL) openerCompleted.countDown()
                    }
                }
                source.webChromeClient = ZCodeWebChromeClient(
                    allowedHost = "example.test",
                    onProgressUpdate = {},
                    onTitleReceived = {},
                    onOpenFileChooser = { _, _ -> false },
                    onOpenWindow = { view, resultMsg ->
                        val opened = windowController.open(view, resultMsg)
                        if (opened) {
                            val popup = requireNotNull((resultMsg.obj as WebView.WebViewTransport).webView)
                            popupReference.set(popup)
                            assertNotNull("控制器必须填入真实子 WebView", popup)
                            val duplicateMessage = Message.obtain().apply { obj = source.WebViewTransport() }
                            duplicateRejected.set(!windowController.open(view, duplicateMessage))

                            // 保留控制器的导航策略与关闭回调，只为网络请求提供本地响应。
                            val originalClient = WebViewCompat.getWebViewClient(popup)
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val blocked = originalClient.shouldOverrideUrlLoading(view, request)
                                    if (request?.url?.scheme == "file" && blocked) nonHttpNavigationBlocked.countDown()
                                    return blocked
                                }

                                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    val blocked = originalClient.shouldOverrideUrlLoading(view, url)
                                    if (url?.startsWith("file:") == true && blocked) nonHttpNavigationBlocked.countDown()
                                    return blocked
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                                    fixtureResponse(request?.url)

                                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse =
                                    fixtureResponse(url?.let(Uri::parse))

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    originalClient.onPageStarted(view, url, favicon)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    originalClient.onPageFinished(view, url)
                                    if (url == FIXTURE_URL) popupLoaded.countDown()
                                }
                            }
                            measureAndLayout(root)
                        }
                        opened
                    },
                )
                source.loadDataWithBaseURL(OPENER_URL, OPENER_HTML, "text/html", "UTF-8", null)
            }

            assertTrue("发起页加载超时", openerLoaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val source = requireNotNull(opener)
            awaitCondition("未取得真实授权弹窗") { popupReference.get() != null }
            val popup = requireNotNull(popupReference.get())
            assertEquals("true", evaluate(source, "!!window.popup && !window.popup.closed"))
            assertTrue("已有弹窗时必须拒绝重复打开", duplicateRejected.get())
            onMain {
                assertEquals(2, parent!!.childCount)
                if (ConnectionWebProfile.isSupported()) {
                    assertEquals(space.profileName, WebViewCompat.getProfile(source).name)
                    assertEquals(space.profileName, WebViewCompat.getProfile(popup).name)
                }
            }
            awaitCondition("弹窗初始页面未就绪") { evaluate(popup, "location.href") == "about:blank" }
            evaluate(popup, "location.href = 'file:///android_asset/authorization-window-blocked.html'; true")
            assertTrue("控制器未拦截非 HTTP 导航", nonHttpNavigationBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("about:blank", evaluate(popup, "location.href"))

            awaitCondition("弹窗打开期间发起页 interval 未持续运行") {
                evaluate(source, "window.intervalTicks").toInt() >= 3
            }
            assertEquals("就绪前不应提前导航", "false", evaluate(source, "window.popupNavigated"))
            evaluate(source, "window.fixtureReady = true")
            assertTrue("静态授权页面加载超时", popupLoaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("fixture-ready", evaluate(popup, "document.body.textContent"))
            assertEquals("true", evaluate(source, "window.popupNavigated"))

            if (closeByPage) {
                // 模拟授权服务自行关闭子窗口；不要等待销毁 WebView 的 JS 回调。
                onMain { popup.evaluateJavascript("window.opener.authorizationReady = true; window.close();", null) }
                awaitCondition("授权页关闭后控制器未移除弹窗") { onMain { parent!!.childCount == 1 } }
            } else {
                // 3.18.0 可只由 opener 轮询完成并整页回跳，不要求成功页调用 window.close。
                evaluate(source, "window.authorizationReady = true")
            }
            assertTrue("发起页轮询未导航至完成页", openerCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("authorization-complete", evaluate(source, "document.body.textContent"))
            onMain {
                assertEquals("关闭回调必须移除授权窗口", 1, parent!!.childCount)
                assertEquals(COMPLETE_URL, source.url)
                assertFalse("关闭后不应再消费返回事件", controller!!.handleBack())
                ownerActive.set(false)
                val inactiveMessage = Message.obtain().apply { obj = source.WebViewTransport() }
                assertFalse("失效 owner 必须拒绝创建窗口", controller!!.open(source, inactiveMessage))
            }
        } finally {
            onMain {
                try {
                    controller?.close()
                } finally {
                    opener?.let { source ->
                        (source.parent as? ViewGroup)?.removeView(source)
                        source.stopLoading()
                        source.destroy()
                    }
                    parent?.removeAllViews()
                }
            }
        }
    }

    private fun fixtureResponse(uri: Uri?): WebResourceResponse {
        val body = when (uri?.toString()) {
            FIXTURE_URL -> "<!doctype html><html><body>fixture-ready</body></html>"
            COMPLETE_URL -> "<!doctype html><html><body>authorization-complete</body></html>"
            else -> null
        }
        // 未声明的请求也返回静态拒绝响应，绝不回退到真实网络。
        return WebResourceResponse(
            "text/html", "UTF-8", if (body != null) 200 else 403,
            if (body != null) "OK" else "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream((body ?: "blocked").toByteArray(Charsets.UTF_8)),
        )
    }

    private fun evaluate(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var result = ""
        onMain {
            webView.evaluateJavascript(script) {
                result = it.orEmpty().trim('"')
                completed.countDown()
            }
        }
        assertTrue("网页脚本回调超时", completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return result
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun measureAndLayout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 800, 1200)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val OPENER_URL = "https://example.test/opener.html"
        private const val FIXTURE_URL = "https://example.test/authorization-fixture"
        private const val COMPLETE_URL = "https://example.test/authorization-complete"
        private val OPENER_HTML = """
            <!doctype html><html><head><meta charset="utf-8"></head><body><script>
              window.popupNavigated = false;
              window.fixtureReady = false;
              window.authorizationReady = false;
              window.intervalTicks = 0;
              window.popup = window.open('about:blank', '_blank');
              window.popupTimer = setInterval(function() {
                window.intervalTicks++;
                if (window.popup && !window.popup.closed && window.fixtureReady && !window.popupNavigated) {
                  window.popupNavigated = true;
                  window.popup.location.href = '$FIXTURE_URL';
                }
                if (window.authorizationReady) {
                  clearInterval(window.popupTimer);
                  window.location.replace('$COMPLETE_URL');
                }
              }, 50);
            </script></body></html>
        """.trimIndent()
    }
}
