package ai.zcode.remote.ui.remote.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import ai.zcode.remote.data.model.ConnectionLoginSpace
import ai.zcode.remote.databinding.ViewAuthorizationWindowBinding

/** 保留发起页的轮询上下文；Z.ai CLI 授权页用普通 WebView 避免 popup 内容接管的 IME 问题。 */
internal class AuthorizationWindowController(
    private val parent: FrameLayout,
    private val loginSpace: ConnectionLoginSpace,
    // 测试包装器必须在首次导航前安装，静态夹具不能短暂访问真实授权服务。
    private val wrapClient: (WebViewClient) -> WebViewClient = { it },
    private val isOwnerActive: () -> Boolean,
) {
    private var binding: ViewAuthorizationWindowBinding? = null
    private var visibleAuthorizationView: WebView? = null
    private var keyboardHeight = 0

    val transportView: WebView? get() = binding?.authorizationWebView
    val authorizationView: WebView? get() = visibleAuthorizationView ?: binding?.authorizationWebView

    @SuppressLint("SetJavaScriptEnabled")
    fun open(opener: WebView, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (!isOwnerActive() || binding != null) return false
        val window = ViewAuthorizationWindowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val child = window.authorizationWebView
        try {
            configureWebView(child, opener.settings.userAgentString)
            child.webViewClient = wrapClient(object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (!isOwnerActive() || !allowsNavigation(request?.url)) return true
                    if (visibleAuthorizationView == null && isCliAuthorizationRequest(opener, request)) {
                        return showCliAuthorization(window, child, opener, request!!.url)
                    }
                    return false
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val uri = url?.let(Uri::parse)
                    if (!isOwnerActive() || !allowsNavigation(uri)) return true
                    // 旧回调没有请求方法和主框架信息，保持原弹窗导航，不跨 WebView 重放请求。
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (!isOwnerActive()) close()
                    window.authorizationOrigin.text = url?.let(Uri::parse)?.host.orEmpty()
                }

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    if (binding?.authorizationWebView === view) close(rendererGone = true)
                    return true
                }
            })
            child.webChromeClient = createPopupChromeClient()
            window.authorizationClose.setOnClickListener { close() }
            binding = window
            parent.addView(window.root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            updateKeyboardHeight(keyboardHeight)
            transport.webView = child
            resultMsg.sendToTarget()
            child.requestFocus()
            return true
        } catch (_: Exception) {
            if (binding === window) binding = null
            (window.root.parent as? ViewGroup)?.removeView(window.root)
            child.destroy()
            return false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showCliAuthorization(
        window: ViewAuthorizationWindowBinding,
        transport: WebView,
        opener: WebView,
        authorizationUrl: Uri,
    ): Boolean {
        if (!isOwnerActive() || visibleAuthorizationView != null) return false
        val display = WebView(window.root.context)
        try {
            configureWebView(display, opener.settings.userAgentString)
            display.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    !isOwnerActive() || !allowsNavigation(request?.url)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    !isOwnerActive() || !allowsNavigation(url?.let(Uri::parse))

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (!isOwnerActive()) close()
                    window.authorizationOrigin.text = url?.let(Uri::parse)?.host.orEmpty()
                }

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    if (visibleAuthorizationView === view) close(rendererGone = true)
                    return true
                }
            }
            display.webChromeClient = createPopupChromeClient()
            val layout = window.root
            val transportIndex = layout.indexOfChild(transport)
            if (transportIndex < 0) {
                display.destroy()
                return false
            }
            // 2026-11-12 真机：接管 popup 的 WebView 有 DOM 焦点却无 IME 输入连接。
            // 保留附着的空白窗口供 WindowProxy 使用，输入由同 Profile 普通 WebView 承载。
            transport.clearFocus()
            transport.visibility = android.view.View.INVISIBLE
            transport.isFocusable = false
            transport.isFocusableInTouchMode = false
            transport.layoutParams = LinearLayout.LayoutParams(1, 1)
            visibleAuthorizationView = display
            layout.addView(display, transportIndex + 1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            updateKeyboardHeight(keyboardHeight)
            display.loadUrl(authorizationUrl.toString())
            display.isFocusableInTouchMode = true
            display.requestFocus()
            return true
        } catch (e: Exception) {
            android.util.Log.w("ZCodeWeb", "could not create isolated authorization view", e)
            display.parent?.let { (it as ViewGroup).removeView(display) }
            display.destroy()
            visibleAuthorizationView = null
            transport.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            transport.visibility = android.view.View.VISIBLE
            transport.isFocusable = true
            transport.isFocusableInTouchMode = true
            return false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, userAgent: String) {
        ConnectionWebProfile.bind(webView, loginSpace)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
    }

    private fun createPopupChromeClient() = object : WebChromeClient() {
        override fun onCloseWindow(window: WebView?) {
            if (binding?.authorizationWebView === window || visibleAuthorizationView === window) close()
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }
    }

    private fun isCliAuthorizationRequest(opener: WebView, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        if (!request.isForMainFrame || !request.method.equals("GET", ignoreCase = true)) return false
        val openerUri = Uri.parse(opener.url ?: return false)
        val callback = Uri.parse(uri.getQueryParameter("redirect_uri") ?: return false)
        return openerUri.scheme.equals("https", ignoreCase = true) &&
            openerUri.host.equals(CLI_HOST, ignoreCase = true) &&
            openerUri.path.orEmpty().startsWith(CLI_REMOTE_PATH) &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(CLI_AUTH_HOST, ignoreCase = true) &&
            uri.path == CLI_AUTH_PATH &&
            uri.getQueryParameter("response_type") == "code" &&
            callback.let {
                it.scheme.equals("https", ignoreCase = true) &&
                    it.host.equals(CLI_HOST, ignoreCase = true) &&
                    it.path == CLI_CALLBACK_PATH
            }
    }

    fun updateKeyboardHeight(height: Int) {
        keyboardHeight = height
        val root = binding?.root ?: return
        val params = root.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin != height) {
            params.bottomMargin = height
            root.layoutParams = params
        }
    }

    fun handleBack(): Boolean {
        val page = authorizationView ?: return false
        if (page.canGoBack()) page.goBack() else close()
        return true
    }

    fun close(rendererGone: Boolean = false) {
        val window = binding ?: return
        binding = null
        val transport = window.authorizationWebView
        val display = visibleAuthorizationView
        visibleAuthorizationView = null
        if (!rendererGone) {
            transport.stopLoading()
            display?.stopLoading()
        }
        parent.removeView(window.root)
        display?.destroy()
        transport.destroy()
    }

    companion object {
        private const val CLI_HOST = "zcode.z.ai"
        private const val CLI_REMOTE_PATH = "/remote/v4"
        private const val CLI_AUTH_HOST = "chat.z.ai"
        private const val CLI_AUTH_PATH = "/auth"
        private const val CLI_CALLBACK_PATH = "/api/v1/oauth/cli/callback/zai"

        internal fun allowsNavigation(uri: Uri?): Boolean = uri != null &&
            (uri.toString() == "about:blank" ||
                (uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()))
    }
}
