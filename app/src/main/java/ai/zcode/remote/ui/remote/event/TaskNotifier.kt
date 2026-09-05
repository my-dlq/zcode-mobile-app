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
import ai.zcode.remote.ui.main.MainActivity

/**
 * 任务事件系统通知：审批请求（高优先级，横幅+声音）与任务完成/失败（默认优先级）。
 * 通知点击回到应用主界面；同一任务的新事件覆盖旧通知（按 taskId+type 定 ID）。
 */
object TaskNotifier {

    private const val CHANNEL_EVENTS = "zcode_task_events"
    private const val CHANNEL_APPROVALS = "zcode_task_approvals"

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
    fun notify(context: Context, event: TaskEventParser.TaskEvent) {
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (title, text, channel) = when (event.type) {
            TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST -> Triple(
                context.getString(R.string.notif_approval_title),
                buildText(context, event, R.string.notif_approval_text),
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
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
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
        // ID 按任务维度稳定：同任务后续事件覆盖同一条通知
        val id = (event.taskId.ifEmpty { "general" } + ":" + event.type.name).hashCode()
        try {
            manager.notify(id, notification)
        } catch (e: Exception) {
            // 通知权限被关闭等情况静默
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
