package ai.zcode.remote.ui.remote.event

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * JS → 原生事件桥：转发页面流量和长连接状态。
 * 信封解码与任务解析仍由 [EnvelopeDecoder]、[TaskEventParser] 负责。
 */
class TaskEventBridge(
    private val sourceId: String,
    private val deviceName: String,
    private val onConnectionState: (ConnectionState) -> Unit,
    private val onEvent: (TaskEventParser.TaskEvent) -> Unit,
) {
    @Volatile
    var enabled = true

    /**
     * JavaScriptInterface 的调用线程必须快速返回。远端的 token/日志流量很大，若在
     * 回调内同步递归解包和遍历 JSON，真正的审批帧会排在它们后面，表现为偶发漏通知。
     * 用单 worker 保持 wire 帧顺序；队列过载时丢弃最旧镜像，随后到达的状态快照仍能补偿
     * 当前 pending 交互。
     */
    private val pendingTraffic = ArrayDeque<String>()
    private val trafficLock = Any()
    private val parserWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZCodeEventParser").apply { isDaemon = true }
    }
    private var parserScheduled = false
    private var queuedBytes = 0
    private var droppedTraffic = 0

    @Volatile
    private var hasReceivedTraffic = false

    @Volatile
    var lastTrafficAtElapsedMs: Long = 0L
        private set

    /**
     * 页面 JS 周期上报的心跳时间。WebView 渲染进程被系统回收、冻结或事件脚本被
     * 页面重建绕过时，心跳会停止；保活服务据此决定何时把事件源切换到隐藏监听。
     */
    @Volatile
    var lastHeartbeatAtElapsedMs: Long = 0L
        private set

    @Volatile
    var lastConnectionState: ConnectionState? = null
        private set

    data class ConnectionState(
        val state: State,
        val transport: String,
        val url: String,
        val reason: String,
        val atElapsedMs: Long,
    ) {
        enum class State { UP, DOWN, DEGRADED }
    }

    @android.webkit.JavascriptInterface
    fun onCaptureReady() {
        if (!enabled) return
        Log.i(TAG, "event capture ready: source=$sourceId")
    }

    @android.webkit.JavascriptInterface
    fun onHeartbeat() {
        if (!enabled) return
        lastHeartbeatAtElapsedMs = SystemClock.elapsedRealtime()
    }

    @android.webkit.JavascriptInterface
    fun onConnectionState(state: String, transport: String, url: String, reason: String) {
        if (!enabled) return
        val normalized = when (state.lowercase()) {
            "up" -> ConnectionState.State.UP
            "down" -> ConnectionState.State.DOWN
            else -> ConnectionState.State.DEGRADED
        }
        val snapshot = ConnectionState(
            state = normalized,
            transport = transport,
            url = url,
            reason = reason,
            atElapsedMs = SystemClock.elapsedRealtime(),
        )
        lastConnectionState = snapshot
        if (normalized == ConnectionState.State.UP) {
            lastTrafficAtElapsedMs = snapshot.atElapsedMs
        }
        Log.i(
            TAG,
            "transport state=${normalized.name.lowercase()} source=$sourceId " +
                "transport=$transport reason=$reason url=${url.take(160)}"
        )
        onConnectionState(snapshot)
    }

    @android.webkit.JavascriptInterface
    fun onTraffic(body: String) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        lastTrafficAtElapsedMs = now
        if (!hasReceivedTraffic) {
            hasReceivedTraffic = true
            Log.i(TAG, "first captured traffic: source=$sourceId")
        }
        val capped = if (body.length > MAX_BYTES) body.take(MAX_BYTES) else body
        synchronized(trafficLock) {
            while (pendingTraffic.isNotEmpty() &&
                (pendingTraffic.size >= MAX_PENDING_FRAMES || queuedBytes + capped.length > MAX_PENDING_BYTES)
            ) {
                queuedBytes -= pendingTraffic.removeFirst().length
                droppedTraffic++
            }
            pendingTraffic.addLast(capped)
            queuedBytes += capped.length
            if (!parserScheduled) {
                parserScheduled = true
                try {
                    parserWorker.execute(::drainTraffic)
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    // dispose() 与 WebView 的最后一个 JS 回调并发时，worker 已停止。
                    // 旧页面的镜像无需再解析，直接清空即可。
                    pendingTraffic.clear()
                    queuedBytes = 0
                    parserScheduled = false
                }
            }
        }
    }

    private fun drainTraffic() {
        while (true) {
            val traffic = synchronized(trafficLock) {
                if (pendingTraffic.isEmpty()) {
                    parserScheduled = false
                    return
                }
                pendingTraffic.removeFirst().also { queuedBytes -= it.length }
            }
            if (!enabled) continue
            EnvelopeDecoder.dispatch(traffic) { text ->
                val events = TaskEventParser.parse(text, deviceName, sourceId)
                for (event in events) {
                    TaskEventParser.logParsed(event)
                    onEvent(event)
                }
            }
            synchronized(trafficLock) {
                if (droppedTraffic > 0) {
                    Log.w(TAG, "event traffic coalesced: dropped=$droppedTraffic source=$sourceId")
                    droppedTraffic = 0
                }
            }
        }
    }

    /** 页面销毁或切换连接时停止解析，避免旧 bridge 的 worker 长驻。 */
    fun dispose() {
        enabled = false
        synchronized(trafficLock) {
            pendingTraffic.clear()
            queuedBytes = 0
            parserScheduled = false
        }
        parserWorker.shutdownNow()
    }

    companion object {
        const val BRIDGE_NAME = "__zcodeNative"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_PENDING_FRAMES = 96
        private const val MAX_PENDING_BYTES = 16 * 1024 * 1024
        private const val TAG = "ZCodeEvent"
    }
}
