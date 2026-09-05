package ai.zcode.remote.ui.remote.event

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * JS → 原生 事件桥：注入脚本把镜像的页面流量（fetch/SSE/WS）与连接状态通过
 * addJavascriptInterface 回传。只暴露两个 @JavascriptInterface 方法，均做
 * 长度截断防御；解析与通知逻辑由 Activity 侧的回调承担。
 */
class TaskEventBridge(
    private val deviceName: String,
    private val onEvent: (TaskEventParser.TaskEvent) -> Unit,
    private val onSessionState: (up: Boolean) -> Unit,
) {
    @Volatile
    var enabled = true

    @android.webkit.JavascriptInterface
    fun onTraffic(body: String) {
        if (!enabled) return
        val capped = if (body.length > 512 * 1024) body.take(512 * 1024) else body
        val event = TaskEventParser.parse(capped, deviceName) ?: return
        TaskEventParser.logParsed(event)
        onEvent(event)
    }

    @android.webkit.JavascriptInterface
    fun onSessionState(state: String) {
        if (!enabled) return
        val up = state == "up"
        Log.d("ZCodeEvent", "session state: $state")
        onSessionState(up)
    }

    companion object {
        const val BRIDGE_NAME = "__zcodeNative"
    }
}
