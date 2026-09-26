package ai.zcode.remote.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.ui.remote.RemoteControlActivity
import ai.zcode.remote.ui.remote.event.EventCaptureScript
import ai.zcode.remote.ui.remote.event.TaskEventBridge
import ai.zcode.remote.ui.remote.event.TaskNotifier
import ai.zcode.remote.ui.main.MainActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat

/**
 * 后台保活前台服务：远程会话期间息屏/切后台后保持进程存活，避免 WebView 会话
 * 被系统回收导致重连。实现参考 zremote（Flutter 版）的 KeepAliveService：
 * - 常驻低优先级通知（IMPORTANCE_LOW，无声音不弹窗）
 * - 息屏时持有 PARTIAL_WAKE_LOCK，防止 CPU 休眠中断 WebSocket 长连接；
 *   亮屏释放（亮屏时系统本身不会深度休眠，无需额外持有）
 * - startForeground 失败（部分 ROM 限制后台启动）时延迟重试一次
 *
 * 事件源托管（单一事件源原则）：远端会话同一时刻只允许一个控制端，因此
 * 主页面 WebView 存活期间本服务绝不创建第二个连接同一远端的 WebView；
 * 只有主页面销毁/进程被系统回收后才用隐藏 WebView 接管事件监听，并在
 * 主页面再次出现时立即销毁隐藏 WebView，避免双 WebView 互相踢线。
 */
class KeepAliveService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        /** 主页面是否正在接管事件源（进程内唯一）。置真时保活服务不得创建隐藏事件源。 */
        @Volatile
        var activityOwnsEventSource = false
            private set

        private const val CHANNEL_ID = "zcode_keepalive"
        private const val NOTIFICATION_ID = 901
        private const val WAKE_LOCK_TAG = "zcode-mobile-app:keepalive"
        private const val RETRY_MS = 2000L
        /** 隐藏事件源 WebView 心跳超时阈值。 */
        private const val MONITOR_HEARTBEAT_TIMEOUT_MS = 35_000L
        /** 主 Activity 事件源心跳/流量超时阈值：超过即视为渲染进程已被系统回收。 */
        private const val ACTIVITY_EVENT_SOURCE_STALE_MS = 60_000L

        private const val EXTRA_URL = "extra_keepalive_url"
        private const val EXTRA_NAME = "extra_keepalive_name"
        private const val EXTRA_SOURCE_ID = "extra_keepalive_source_id"
        private const val TAG = "ZCodeKeepAlive"
        private const val MONITOR_HEALTH_INTERVAL_MS = 30_000L
        private const val MONITOR_START_TIMEOUT_MS = 20_000L
        private const val MONITOR_DOWN_RETRY_MS = 5_000L

        fun keepAlive(
            context: Context,
            url: String = "",
            name: String = "",
            sourceId: String = "",
        ) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                if (url.isNotEmpty()) putExtra(EXTRA_URL, url)
                if (name.isNotEmpty()) putExtra(EXTRA_NAME, name)
                if (sourceId.isNotEmpty()) putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 兼容旧调用点：保留签名语义（更新目标并保持服务运行）。 */
        fun start(
            context: Context,
            url: String = "",
            name: String = "",
            sourceId: String = "",
        ) = keepAlive(context, url, name, sourceId)

        /** 主页面取得事件源所有权。必须在加载远程页面前调用，请求服务销毁残留隐藏源。 */
        fun acquireEventSource(context: Context, url: String, name: String, sourceId: String) {
            activityOwnsEventSource = true
            keepAlive(context, url, name, sourceId)
        }

        /** 主页面销毁后交还事件源所有权，保活服务接管后台事件监听。 */
        fun releaseEventSource(context: Context, url: String, name: String, sourceId: String) {
            activityOwnsEventSource = false
            keepAlive(context, url, name, sourceId)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var monitorWebView: WebView? = null
    private var monitorBridge: TaskEventBridge? = null
    private var monitorCaptureScript: ScriptHandler? = null
    private var monitorUrl: String = ""
    private var monitorName: String = ""
    private var monitorSourceId: String = ""
    private var monitorActiveSourceId: String = ""
    private var monitorStartedAtElapsedMs: Long = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> holdWakeLock()
                Intent.ACTION_SCREEN_ON -> releaseWakeLock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotEmpty() }?.let {
            monitorUrl = it
            monitorName = intent.getStringExtra(EXTRA_NAME) ?: ""
            monitorSourceId = intent.getStringExtra(EXTRA_SOURCE_ID) ?: ""
        }
        refreshEventSource()
        if (!promoteToForeground()) {
            handler.postDelayed({
                if (isRunning && !promoteToForeground()) stopSelf()
            }, RETRY_MS)
        }
        // 启动时屏幕已灭则直接持有（服务可能在后台被拉起）
        if (!powerManager.isInteractive) holdWakeLock()
        scheduleHealthCheck()
        return START_STICKY
    }

    private fun promoteToForeground(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
        }
        disposeEventSource()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val powerManager: PowerManager
        get() = getSystemService(POWER_SERVICE) as PowerManager

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.keepalive_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.keepalive_channel_desc)
                    setShowBadge(false)
                }
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.apply {
            if (Build.VERSION.SDK_INT >= 26) {
                setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            }
        }
            .setContentTitle(getString(R.string.keepalive_notif_title))
            .setContentText(getString(R.string.keepalive_notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 任务审批/提问是高优先级通知。若保活通知未分组，Android 会把
            // 两者自动聚合成一组，通知栏只显示“2 条”并稀释任务提醒。
            .setGroup(CHANNEL_ID)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun holdWakeLock() {
        val lock = wakeLock ?: powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun startHiddenMonitor() {
        val url = monitorUrl
        if (url.isBlank()) {
            Log.w(TAG, "hidden monitor skipped: no target url")
            return
        }
        val webView = WebView(this)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "+
                "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        val sourceId = monitorSourceId.ifBlank { url }
        val bridge = TaskEventBridge(
            sourceId = sourceId,
            deviceName = monitorName.ifBlank { "ZCode 远程工作区" },
            onConnectionState = {
                // 隐藏监听只补事件，不参与页面自身的恢复调度。
            },
            onEvent = { event ->
                TaskNotifier.notify(this, event)
            },
        )
        monitorBridge = bridge
        monitorActiveSourceId = sourceId
        webView.addJavascriptInterface(bridge, TaskEventBridge.BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    EventCaptureScript.build(TaskEventBridge.BRIDGE_NAME),
                    null,
                )
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                Log.e(TAG, "hidden event monitor renderer gone; will recreate on next health check")
                // 渲染进程已死，页面连接随之断开。立即销毁旧 WebView，由下一次巡检
                // 重建——重建前先确认主页面没有重新出现，避免双事件源并存。
                handler.post { disposeEventSource() }
                return true
            }
        }
        val origin = runCatching {
            val uri = Uri.parse(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) url
            else "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        }.getOrDefault(url)
        runCatching {
            monitorCaptureScript = WebViewCompat.addDocumentStartJavaScript(
                webView,
                EventCaptureScript.build(TaskEventBridge.BRIDGE_NAME),
                setOf(origin),
            )
        }.onFailure {
            Log.w(TAG, "hidden monitor document-start inject failed: ${it.message}")
        }
        monitorWebView = webView
        monitorStartedAtElapsedMs = SystemClock.elapsedRealtime()
        webView.loadUrl(url)
        Log.i(TAG, "hidden event monitor started: source=$sourceId url=${url.take(160)}")
    }


    private fun restartHiddenMonitor() {
        disposeEventSource()
        refreshEventSource()
    }

    private fun disposeEventSource() {
        monitorCaptureScript?.let {
            runCatching { it.remove() }
        }
        monitorCaptureScript = null
        monitorBridge?.dispose()
        monitorBridge = null
        monitorWebView?.let {
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
        monitorWebView = null
        monitorActiveSourceId = ""
        monitorStartedAtElapsedMs = 0L
    }

    private fun scheduleHealthCheck() {
        handler.removeCallbacks(healthCheckRunnable)
        handler.postDelayed(healthCheckRunnable, MONITOR_HEALTH_INTERVAL_MS)
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            refreshEventSource()
            handler.postDelayed(this, MONITOR_HEALTH_INTERVAL_MS)
        }
    }

    private fun refreshEventSource() {
        if (activityOwnsEventSource || RemoteControlActivity.hasLiveInstance()) {
            // 主页面活着不等于事件源健康：vivo 等 ROM 会在后台回收 WebView 渲染
            // 进程而保留 Activity（前台服务保住了宿主进程）。渲染进程死后页面 JS
            // 心跳/流量停止，事件捕获整条失效，表现为“弹几个通知后彻底收不到”。
            // 周期巡检发现心跳超时且 Activity 后台不可见时，让 Activity 交接事件源
            // 给本服务的隐藏监听（Activity 内部负责释放所有权并销毁自身）。
            val handedOver = RemoteControlActivity
                .requestEventSourceHandoverIfStale(ACTIVITY_EVENT_SOURCE_STALE_MS)
            if (!handedOver && monitorWebView != null) {
                Log.i(TAG, "primary activity alive; stopping hidden monitor")
                disposeEventSource()
            }
            return
        }
        if (monitorUrl.isBlank()) {
            val lastUrl = ConnectionRepository.getInstance(this).getLastActiveUrl()
            if (lastUrl.isNullOrBlank()) {
                // 无任何历史连接：隐藏事件源没有可接管目标，保持空转等用户下次连接。
                disposeEventSource()
                return
            }
            monitorUrl = lastUrl
            monitorName = ConnectionRepository.getInstance(this).getLastActiveName() ?: ""
            monitorSourceId = monitorUrl
        }
        val desiredSourceId = monitorSourceId.ifBlank { monitorUrl }
        if (monitorWebView != null && monitorActiveSourceId == desiredSourceId) {
            // 已有目标一致的隐藏事件源：仅做健康自检。
            checkHiddenMonitorHealth()
            return
        }
        disposeEventSource()
        runCatching { startHiddenMonitor() }
            .onFailure {
                Log.e(TAG, "hidden event monitor start failed", it)
                disposeEventSource()
            }
    }

    /** 隐藏事件源健康自检（仅巡检其自身，不替代主事件源判断）。 */
    private fun checkHiddenMonitorHealth() {
        val bridge = monitorBridge ?: return
        val state = bridge.lastConnectionState
        val now = SystemClock.elapsedRealtime()
        val noTraffic = monitorStartedAtElapsedMs > 0 &&
            now - monitorStartedAtElapsedMs > MONITOR_START_TIMEOUT_MS &&
            bridge.lastTrafficAtElapsedMs == 0L
        val heartbeatStale = monitorStartedAtElapsedMs > 0 &&
            now - monitorStartedAtElapsedMs > MONITOR_HEARTBEAT_TIMEOUT_MS &&
            bridge.lastHeartbeatAtElapsedMs > 0L &&
            now - bridge.lastHeartbeatAtElapsedMs > MONITOR_HEARTBEAT_TIMEOUT_MS
        val down = state != null &&
            state.state != TaskEventBridge.ConnectionState.State.UP &&
            now - state.atElapsedMs > MONITOR_DOWN_RETRY_MS
        if (noTraffic || heartbeatStale || down) {
            Log.w(TAG, "hidden event monitor unhealthy; reloading")
            restartHiddenMonitor()
        }
    }
}
