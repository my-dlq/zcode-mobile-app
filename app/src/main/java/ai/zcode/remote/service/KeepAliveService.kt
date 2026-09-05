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
import ai.zcode.remote.R
import ai.zcode.remote.ui.main.MainActivity

/**
 * 后台保活前台服务：远程会话期间息屏/切后台后保持进程存活，避免 WebView 会话
 * 被系统回收导致重连。实现参考 zremote（Flutter 版）的 KeepAliveService：
 * - 常驻低优先级通知（IMPORTANCE_LOW，无声音不弹窗）
 * - 息屏时持有 PARTIAL_WAKE_LOCK，防止 CPU 休眠中断 WebSocket 长连接；
 *   亮屏释放（亮屏时系统本身不会深度休眠，无需额外持有）
 * - startForeground 失败（部分 ROM 限制后台启动）时延迟重试一次
 */
class KeepAliveService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "zcode_keepalive"
        private const val NOTIFICATION_ID = 901
        private const val WAKE_LOCK_TAG = "zcode-mobile-app:keepalive"
        private const val RETRY_MS = 2000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

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
        if (!promoteToForeground()) {
            handler.postDelayed({
                if (isRunning && !promoteToForeground()) stopSelf()
            }, RETRY_MS)
        }
        // 启动时屏幕已灭则直接持有（服务可能在后台被拉起）
        if (!powerManager.isInteractive) holdWakeLock()
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
        return builder
            .setContentTitle(getString(R.string.keepalive_notif_title))
            .setContentText(getString(R.string.keepalive_notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
}
