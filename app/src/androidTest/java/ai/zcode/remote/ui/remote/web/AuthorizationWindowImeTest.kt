package ai.zcode.remote.ui.remote.web

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.model.ConnectionLoginSpace
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AuthorizationWindowImeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun ordinaryWebViewImeBaseline() = withFixture(openPopup = false) { activity ->
        assertEquals("abcde", enterText(requireNotNull(activity.opener)))
    }

    @Test
    fun oauthPopupVisibleWebViewImeAB() = withFixture(openPopup = true) { activity ->
        val transport = requireNotNull(activity.transport)
        val visible = requireNotNull(activity.authorization)
        assertTrue("transport WebView remains attached", onMain { transport.isAttachedToWindow })
        assertEquals("transport is hidden after fallback", View.INVISIBLE, onMain { transport.visibility })
        assertTrue("authorization WebView is attached to the Activity Window", onMain { visible.isAttachedToWindow })

        // Legacy transport behavior is diagnostic only; it can vary across WebView/device versions.
        val transportResult = runCatching { enterText(transport) }.getOrNull()
        val visibleResult = enterText(visible)
        assertEquals("visible authorization WebView IME", "abcde", visibleResult)
        android.util.Log.i(TAG, "IME A/B transport=${transportResult ?: "unavailable"}, visible=$visibleResult")
    }

    private fun withFixture(openPopup: Boolean, test: (AuthorizationImeFixtureActivity) -> Unit) {
        val component = android.content.ComponentName(
            instrumentation.targetContext,
            AuthorizationImeFixtureActivity::class.java,
        )
        val activity = instrumentation.startActivitySync(
            Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AuthorizationImeFixtureActivity
        try {
            org.junit.Assume.assumeTrue("WebView profiles are required", ConnectionWebProfile.isSupported())
            onMain {
                val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZCodeRemote)
                val root = activity.content
                val space = ConnectionLoginSpace("authorization-ime-${UUID.randomUUID()}")
                activity.loginSpace = space
                activity.profileSupported = ConnectionWebProfile.isSupported()
                val opener = WebView(context)
                activity.opener = opener
                ConnectionWebProfile.bind(opener, space)
                opener.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = openPopup
                    setSupportMultipleWindows(openPopup)
                }
                root.addView(opener, FrameLayout.LayoutParams(-1, -1))
                lateinit var controller: AuthorizationWindowController
                controller = AuthorizationWindowController(
                    root,
                    space,
                    wrapClient = { original ->
                        FixtureClient(original) {
                            controller.authorizationView
                                ?.takeIf { it !== controller.transportView }
                                ?.let { activity.authorization = it }
                        }
                    },
                ) { !activity.isFinishing && !activity.isDestroyed }
                activity.closeController = { controller.close() }
                opener.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) = fixtureResponse(request?.url)
                    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                    override fun shouldInterceptRequest(view: WebView?, url: String?) = fixtureResponse(url?.let(Uri::parse))
                }
                opener.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean {
                        if (view == null || resultMsg == null || !controller.open(view, resultMsg)) return false
                        val transport = requireNotNull(controller.transportView)
                        activity.transport = transport
                        val original = WebViewCompat.getWebViewClient(transport)
                        transport.webViewClient = FixtureClient(original) {
                            val display = controller.authorizationView
                            if (display != null && display !== transport && activity.authorization !== display) {
                                activity.authorization = display
                                display.webViewClient = FixtureClient(WebViewCompat.getWebViewClient(display)) { }
                            }
                        }
                        return true
                    }
                }
                opener.loadDataWithBaseURL(OPENER_URL, if (openPopup) OPENER_HTML else INPUT_HTML, "text/html", "UTF-8", null)
            }
            val opener = requireNotNull(activity.opener)
            awaitCondition("opener static page did not load") {
                evaluate(opener, "document.readyState") == "complete" &&
                    evaluate(opener, "document.getElementById('ime-fixture') !== null") == "true"
            }
            if (openPopup) {
                awaitCondition("OAuth authorization view was not created") { activity.authorization != null }
                val visible = requireNotNull(activity.authorization)
                awaitCondition("authorization static page did not load") {
                    evaluate(visible, "document.getElementById('ime-fixture') !== null") == "true"
                }
            }
            test(activity)
        } finally {
            onMain { activity.finish() }
        }
    }

    private class FixtureClient(
        private val original: WebViewClient,
        private val afterNavigation: () -> Unit,
    ) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val blocked = original.shouldOverrideUrlLoading(view, request)
            afterNavigation()
            return blocked
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val blocked = original.shouldOverrideUrlLoading(view, url)
            afterNavigation()
            return blocked
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) = fixtureResponse(request?.url)
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun shouldInterceptRequest(view: WebView?, url: String?) = fixtureResponse(url?.let(Uri::parse))
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = original.onPageStarted(view, url, favicon)
        override fun onPageFinished(view: WebView?, url: String?) = original.onPageFinished(view, url)
        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?) =
            original.onRenderProcessGone(view, detail)
    }

    private fun enterText(webView: WebView): String {
        awaitCondition("fixture input is missing") { evaluate(webView, "document.getElementById('ime-fixture') !== null") == "true" }
        val center = JSONArray(evaluate(webView, "(function(){var r=document.getElementById('ime-fixture').getBoundingClientRect();return JSON.stringify([r.left+r.width/2,r.top+r.height/2])})()"))
        val xy = IntArray(2)
        onMain { webView.getLocationOnScreen(xy) }
        val x = xy[0] + center.getDouble(0).toFloat()
        val y = xy[1] + center.getDouble(1).toFloat()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        })
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        })
        awaitCondition("touch did not focus the HTML input") { evaluate(webView, "document.activeElement.id") == "ime-fixture" }
        val editorInfo = EditorInfo()
        val connection = onMain { webView.onCreateInputConnection(editorInfo) }
        assertNotNull("WebView must provide an InputConnection", connection)
        assertEquals("text input", InputType.TYPE_CLASS_TEXT, editorInfo.inputType and InputType.TYPE_MASK_CLASS)
        onMain {
            val input = requireNotNull(connection)
            assertTrue("commitText rejected", input.commitText("abc", 1))
            assertTrue("setComposingText rejected", input.setComposingText("de", 1))
            assertTrue("finishComposingText rejected", input.finishComposingText())
        }
        awaitCondition("IME text did not reach the DOM") { evaluate(webView, "document.getElementById('ime-fixture').value") == "abcde" }
        return evaluate(webView, "document.getElementById('ime-fixture').value")
    }

    private fun evaluate(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("")
        onMain { webView.evaluateJavascript(script) { value -> result.set(value.orEmpty().trim('"')); latch.countDown() } }
        assertTrue("JavaScript callback timed out", latch.await(TIMEOUT, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT)
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    companion object {
        private const val TAG = "AuthorizationWindowImeTest"
        private const val TIMEOUT = 15L
        private const val CALLBACK = "https://zcode.z.ai/api/v1/oauth/cli/callback/zai"
        private const val OPENER_URL = "https://zcode.z.ai/remote/v4?fixture=ime"
        private const val AUTH_URL = "https://chat.z.ai/auth?response_type=code&redirect_uri=https%3A%2F%2Fzcode.z.ai%2Fapi%2Fv1%2Foauth%2Fcli%2Fcallback%2Fzai&client_id=test&state=fixture"
        private val OPENER_HTML = """
            <!doctype html><html><body><script>window.popup=window.open('$AUTH_URL','_blank');</script>
            <input id="ime-fixture" type="text" autocomplete="off" style="position:absolute;left:24px;top:120px;width:280px;height:56px;font-size:20px"></body></html>
        """.trimIndent()
        private val INPUT_HTML = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body>
            <input id="ime-fixture" type="text" autocomplete="off" style="position:absolute;left:24px;top:120px;width:280px;height:56px;font-size:20px"></body></html>
        """.trimIndent()

        private fun fixtureResponse(uri: Uri?): WebResourceResponse {
            val opener = uri?.scheme == "https" && uri.host == "zcode.z.ai" && uri.path == "/remote/v4"
            val auth = uri?.scheme == "https" && uri.host == "chat.z.ai" && uri.path == "/auth" &&
                uri.getQueryParameter("response_type") == "code" && uri.getQueryParameter("redirect_uri") == CALLBACK &&
                uri.getQueryParameter("client_id") == "test" && uri.getQueryParameter("state") == "fixture"
            val html = when { opener -> OPENER_HTML; auth -> INPUT_HTML; else -> "<!doctype html><html>blocked</html>" }
            return WebResourceResponse(
                "text/html", "UTF-8", if (opener || auth) 200 else 403,
                if (opener || auth) "OK" else "Blocked", mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
            )
        }
    }
}
