package ai.zcode.remote.ui.remote.web

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class ZCodeWebChromeClient(
    private val allowedHost: String?,
    private val onProgressUpdate: (progress: Int) -> Unit,
    private val onTitleReceived: (title: String) -> Unit,
    private val onOpenFileChooser: (filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams) -> Boolean
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressUpdate(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!title.isNullOrBlank()) {
            onTitleReceived(title)
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        val originHost = request?.origin?.host
        val allowed = !allowedHost.isNullOrBlank() && originHost.equals(allowedHost, ignoreCase = true)
        val resources = request?.resources.orEmpty().filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }.toTypedArray()
        if (allowed && resources.isNotEmpty()) {
            request?.grant(resources)
        } else {
            request?.deny()
        }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        if (filePathCallback != null && fileChooserParams != null) {
            return onOpenFileChooser(filePathCallback, fileChooserParams)
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
    }

    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
        if (consoleMessage != null) {
            android.util.Log.d("ZCodeWeb", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (line: ${consoleMessage.lineNumber()})")
        }
        return super.onConsoleMessage(consoleMessage)
    }
}
