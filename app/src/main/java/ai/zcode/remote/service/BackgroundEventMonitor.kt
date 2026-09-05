package ai.zcode.remote.service

import android.content.Context
import android.util.Log
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.ui.remote.event.TaskEventParser
import ai.zcode.remote.ui.remote.event.TaskNotifier
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 后台事件监听：为非活跃连接维持 WebSocket 连接，镜像事件到
 * TaskEventParser → TaskNotifier，实现"切到会话 A 时会话 B 的
 * 审批/完成也能收到系统通知"。
 *
 * 工作原理：
 * - RemoteControlActivity 的 EventCaptureScript 在 WS open 时把 URL
 *   传给原生并保存到 ConnectionRepository
 * - 本管理器为每个有 WS URL 的连接维持一个后台 WebSocket
 * - 收到的消息走与 WebView 相同的解析管线（base64 解码 + 差分状态机）
 * - 事件触发 TaskNotifier 系统通知
 *
 * 生命周期：由 KeepAliveService 持有，服务销毁时全部断开。
 */
class BackgroundEventMonitor(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不设读超时
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val repository = ConnectionRepository.getInstance(context)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val parsers = ConcurrentHashMap<String, TaskEventParser>()

    /** 为所有有 WS URL 的连接启动后台监听。 */
    fun startAll() {
        val connections = repository.getAllConnections()
        for (conn in connections) {
            val wsUrl = repository.getWsUrl(conn.id) ?: continue
            startForConnection(conn.id, conn.name, wsUrl)
        }
    }

    /** 为单个连接启动后台 WebSocket 监听。 */
    fun startForConnection(connectionId: String, deviceName: String, wsUrl: String) {
        if (sockets.containsKey(connectionId)) return // 已在监听
        Log.d(TAG, "starting background WS for $deviceName: $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        val listener = createListener(connectionId, deviceName)
        val ws = client.newWebSocket(request, listener)
        sockets[connectionId] = ws
    }

    /** 停止所有后台监听。 */
    fun stopAll() {
        for ((id, ws) in sockets) {
            Log.d(TAG, "stopping background WS for $id")
            ws.close(1000, "service destroyed")
        }
        sockets.clear()
        parsers.clear()
    }

    /** 刷新：新连接加入或有 WS URL 更新时调用。 */
    fun refresh() {
        val connections = repository.getAllConnections()
        val activeIds = connections.mapNotNull { conn ->
            val wsUrl = repository.getWsUrl(conn.id)
            if (wsUrl != null) {
                if (!sockets.containsKey(conn.id)) {
                    startForConnection(conn.id, conn.name, wsUrl)
                }
                conn.id
            } else null
        }.toSet()
        // 断开已删除或失去 WS URL 的连接
        val toRemove = sockets.keys - activeIds
        for (id in toRemove) {
            sockets.remove(id)?.close(1000, "connection removed")
            parsers.remove(id)
        }
    }

    private fun createListener(connectionId: String, deviceName: String) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "background WS open: $deviceName")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 走与 WebView 相同的解析管线（信封解码 + 事件解析）
                ai.zcode.remote.ui.remote.event.EnvelopeDecoder.dispatch(text) { decoded ->
                    val events = TaskEventParser.parse(decoded, deviceName)
                    for (event in events) {
                        TaskEventParser.logParsed(event)
                        TaskNotifier.notify(context, event)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "background WS closing: $deviceName code=$code")
                sockets.remove(connectionId)
                parsers.remove(connectionId)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 打完整异常与响应码：t.message 常为 null，看不到真实失败原因
                Log.w(TAG, "background WS failure: $deviceName code=${response?.code} " +
                    "err=${t.javaClass.simpleName}: ${t.message}", t)
                sockets.remove(connectionId)
                parsers.remove(connectionId)
                // 延迟重连
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val wsUrl = repository.getWsUrl(connectionId)
                    if (wsUrl != null) {
                        startForConnection(connectionId, deviceName, wsUrl)
                    }
                }, RECONNECT_MS)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "background WS closed: $deviceName code=$code reason=$reason")
            }
        }

    companion object {
        private const val TAG = "ZCodeEvent"
        /** WS 断开后重连延迟：5s 快速重连，缩短事件盲区（原 30s 太长）。 */
        private const val RECONNECT_MS = 5_000L

        @Volatile
        private var instance: BackgroundEventMonitor? = null

        /** 由 KeepAliveService 持有实例时注册，供外部刷新调用。 */
        fun registerInstance(monitor: BackgroundEventMonitor) {
            instance = monitor
        }

        fun unregisterInstance(monitor: BackgroundEventMonitor) {
            if (instance === monitor) instance = null
        }

        /** 外部调用：刷新后台监听（新连接加入或 WS URL 更新时）。 */
        fun refreshInstance() {
            instance?.refresh()
        }
    }
}
