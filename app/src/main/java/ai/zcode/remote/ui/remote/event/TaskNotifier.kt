package ai.zcode.remote.ui.remote.event

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.ui.remote.RemoteControlActivity

/**
 * 任务事件系统通知：审批请求（高优先级，横幅+声音）与任务完成/失败（默认优先级）。
 * 通知点击进入对应连接的远程控制页并跳转到任务会话。
 *
 * 通知偏好：总开关关闭后所有事件不通知；各事件类型（审批/提问/完成/失败）
 * 可独立开关。事件源为当前连接的 WebView 页面（单通道），同一事件理论上
 * 只会触发一次；保留极短去重窗口仅防同一次 snapshot 镜像的重复推送，
 * 不会过滤"短时间内连续的真实事件"（如 30 秒内同任务再次审批）。
 */
object TaskNotifier {

    private const val CHANNEL_EVENTS = "zcode_task_events"
    private const val CHANNEL_APPROVALS = "zcode_task_approvals"

    /** 同 taskId+type 的事件的极小去重窗口（仅防 snapshot 镜像重复，不拦连续事件）。 */
    private const val DEDUP_WINDOW_MS = 3_000L
    private val recentNotified = HashMap<String, Long>()

    /**
     * RESOLVED 冷却窗口：远端 WS 重连/刷新快照时会短暂把 permissionCount 从 N→0 再补回，
     * 触发"假 resolved"把刚弹出的审批通知撤掉。冷却期内的 resolved 视为抖动忽略。
     * 实测远端在用户未操作时 ~11s 后会自发发 RESOLVED（多端共用会话），
     * 冷却窗取 30s 覆盖该窗口，避免"通知几秒消失"复发。
     * 副作用：用户在 30s 内点同意/拒绝时旧通知暂不撤销，但不影响新通知弹出。
     */
    private const val RESOLVE_COOLDOWN_MS = 30_000L

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, context.getString(R.string.notif_channel_approvals), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_approvals_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.notif_channel_events), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_events_desc)
            }
        )
    }

    /** 发布任务事件通知；重复事件（同任务同类型）只刷新不重复弹。 */
    @Synchronized
    fun notify(context: Context, event: TaskEventParser.TaskEvent) {
        // 用户正停留在该任务会话页（页面可见且显示此会话）：
        // 审批/提问弹层本身就在页面上，系统通知是额外打扰，跳过
        if (event.taskId.isNotEmpty() &&
            ai.zcode.remote.ui.remote.RemoteControlActivity.isForegroundSession(event.taskId)
        ) {
            return
        }

        // resolved 是撤回信号：撤销该任务的审批/提问通知，不发新通知
        // 但需冷却窗口保护：通知刚弹出 5s 内的 resolved 判定为远端快照抖动的假撤回，忽略
        if (event.type == TaskEventParser.TaskEvent.Type.RESOLVED) {
            val lastRequestTime = recentNotified[event.taskId + ":PERMISSION_REQUEST"]
                ?: recentNotified[event.taskId + ":ELICITATION_REQUEST"]
                ?: 0L
            val sinceRequest = System.currentTimeMillis() - lastRequestTime
            if (sinceRequest < RESOLVE_COOLDOWN_MS) {
                return // 冷却期内忽略，避免假撤回
            }
            cancelPending(context, event.taskId)
            return
        }

        // 通知偏好检查：总开关 + 事件类型开关
        val settings = AppSettingsRepository.getInstance(context)
        if (!settings.isNotificationEnabled()) return
        val typeEnabled = when (event.type) {
            TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST -> settings.isNotifApprovalEnabled()
            TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST -> settings.isNotifElicitationEnabled()
            TaskEventParser.TaskEvent.Type.TASK_COMPLETED -> settings.isNotifCompletedEnabled()
            TaskEventParser.TaskEvent.Type.TASK_FAILED -> settings.isNotifFailedEnabled()
            else -> false
        }
        if (!typeEnabled) return

        // 时间窗口去重：前台 WS 和后台 WS 可能各触发一次同一事件
        val dedupKey = event.taskId + ":" + event.type.name
        val now = System.currentTimeMillis()
        val lastTime = recentNotified[dedupKey] ?: 0L
        if (now - lastTime < DEDUP_WINDOW_MS) return
        recentNotified[dedupKey] = now
        // 防御性清理：最多保留 100 条去重记录
        if (recentNotified.size > 100) {
            val oldest = recentNotified.minByOrNull { it.value }?.key
            if (oldest != null) recentNotified.remove(oldest)
        }

        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (title, text, channel) = when (event.type) {
            TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST -> Triple(
                context.getString(R.string.notif_approval_title),
                buildText(context, event, R.string.notif_approval_text),
                CHANNEL_APPROVALS
            )
            TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST -> Triple(
                context.getString(R.string.notif_elicitation_title),
                buildText(context, event, R.string.notif_elicitation_text),
                CHANNEL_APPROVALS
            )
            TaskEventParser.TaskEvent.Type.TASK_COMPLETED -> Triple(
                context.getString(R.string.notif_completed_title),
                buildText(context, event, R.string.notif_completed_text),
                CHANNEL_EVENTS
            )
            TaskEventParser.TaskEvent.Type.TASK_FAILED -> Triple(
                context.getString(R.string.notif_failed_title),
                buildText(context, event, R.string.notif_failed_text),
                CHANNEL_EVENTS
            )
            else -> return
        }
        // 新事件到来时先取消同任务的所有旧通知（跨通道），避免残留
        if (event.taskId.isNotEmpty()) {
            manager.cancel(event.taskId.hashCode())
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            buildLaunchIntent(context, event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        // ID 按任务维度稳定：同任务的新事件覆盖旧通知（不带 type，
        // 避免同一任务的失败和成功通知并存导致"上次的失败通知"残留）
        val id = event.taskId.ifEmpty { "general" }.hashCode()
        try {
            manager.notify(id, notification)
        } catch (e: Exception) {
            // 通知权限被关闭等情况静默
        }
    }

    /** 撤销指定任务的通知（用户已在别处处理）。 */
    private fun cancelPending(context: Context, taskId: String) {
        if (taskId.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(taskId.hashCode())
    }

    /**
     * 构造通知点击后的启动 Intent：通过 deviceName 从连接仓库反查连接，
     * 打开对应远程控制页并携带 taskId 跳转到任务会话；查不到时退化为首页。
     */
    private fun buildLaunchIntent(context: Context, event: TaskEventParser.TaskEvent): Intent {
        val conn = findConnection(context, event.deviceName)
        return if (conn != null) {
            Intent(context, RemoteControlActivity::class.java).apply {
                putExtra(RemoteControlActivity.EXTRA_URL, conn.url)
                putExtra(RemoteControlActivity.EXTRA_NAME, conn.name)
                if (event.taskId.isNotEmpty()) {
                    putExtra(RemoteControlActivity.EXTRA_TASK_ID, event.taskId)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    /** 按设备名从连接仓库反查连接。 */
    private fun findConnection(context: Context, deviceName: String): ai.zcode.remote.data.model.RemoteConnection? {
        if (deviceName.isBlank()) return null
        return try {
            val repo = ConnectionRepository.getInstance(context)
            repo.getAllConnections().firstOrNull { it.name == deviceName }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildText(context: Context, event: TaskEventParser.TaskEvent, templateRes: Int): String {
        val title = event.taskTitle.ifBlank { context.getString(R.string.notif_task_unnamed) }
        return if (event.deviceName.isBlank()) {
            context.getString(templateRes, title)
        } else {
            context.getString(templateRes, title) + " · " + event.deviceName
        }
    }
}
