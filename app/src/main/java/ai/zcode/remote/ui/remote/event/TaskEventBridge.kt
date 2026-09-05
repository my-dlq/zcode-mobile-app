package ai.zcode.remote.ui.remote.event

import android.util.Log

/**
 * JS → 原生 事件桥：注入脚本把镜像的页面流量（fetch/SSE/WS）与连接状态通过
 * addJavascriptInterface 回传。信封解码（base64/分片重组/递归解嵌套）由
 * [EnvelopeDecoder] 承担，本类只做桥接转发。
 */
class TaskEventBridge(
    private val deviceName: String,
    private val onEvent: (TaskEventParser.TaskEvent) -> Unit,
    private val onSessionState: (up: Boolean) -> Unit,
    private val wsUrlCallback: (url: String) -> Unit = {},
) {
    @Volatile
    var enabled = true

    @android.webkit.JavascriptInterface
    fun onTraffic(body: String) {
        if (!enabled) return
        val capped = if (body.length > MAX_BYTES) body.take(MAX_BYTES) else body
        EnvelopeDecoder.dispatch(capped) { text ->
            val events = TaskEventParser.parse(text, deviceName)
            for (event in events) {
                TaskEventParser.logParsed(event)
                onEvent(event)
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun onSessionState(state: String) {
        if (!enabled) return
        val up = state == "up"
        Log.d(TAG, "session state: $state")
        onSessionState(up)
    }

    /** WS 连接建立时回传 URL：KeepAliveService 后台重连用。 */
    @android.webkit.JavascriptInterface
    fun onWsUrl(url: String) {
        if (!enabled || url.isEmpty()) return
        Log.d(TAG, "ws url: $url")
        wsUrlCallback(url)
    }

    companion object {
        const val BRIDGE_NAME = "__zcodeNative"
        private const val TAG = "ZCodeEvent"
        private const val MAX_BYTES = 512 * 1024
    }
}
