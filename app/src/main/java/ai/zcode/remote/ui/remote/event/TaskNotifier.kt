package ai.zcode.remote.ui.remote.event

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.ui.remote.RemoteControlActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 持久化通知信箱的生产者/消费者。WebView 回调仅入库，单线程消费者再发布系统通知。
 * 同一个 eventKey 被标记为已投递后会保留，重连快照及用户划掉通知均不会令它再次弹出。
 */
object TaskNotifier {
    private const val CHANNEL_EVENTS = "zcode_task_events"
    private const val CHANNEL_APPROVALS = "zcode_task_approvals"
    private const val TAG = "ZCodeEvent"
    private const val RESOLVE_COOLDOWN_MS = 30_000L
    private const val MAX_RECENT_REQUESTS = 256
    private const val RETRY_DELAY_MS = 15_000L
    private const val FOREGROUND_CHECK_DELAY_MS = 1_000L

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ZCodeNotificationConsumer").apply { isDaemon = true }
    }
    /** taskId → 最近一次已投递交互请求的 eventKey，用于让 resolved 精确配对。 */
    private val recentRequestKeys = HashMap<String, String>()
    /** eventKey → 最近一次投递时间，用于分辨真实的交互结束与快照抖动。 */
    private val recentRequestTimes = HashMap<String, Long>()
    @Volatile private var inboxInstance: TaskNotificationInbox? = null
    private var consumerRunning = false

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, context.getString(R.string.notif_channel_approvals),
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_approvals_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.notif_channel_events),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_events_desc)
            },
        )
    }

    /** WebView 事件生产入口，不在回调线程同步发布通知。 */
    fun notify(context: Context, event: TaskEventParser.TaskEvent) {
        val appContext = context.applicationContext
        if (event.type == TaskEventParser.TaskEvent.Type.RESOLVED) {
            handleResolved(appContext, event)
            return
        }
        if (!isNotifiable(event.type)) return
        if (inbox(appContext).enqueue(event)) {
            Log.d(TAG, "notification enqueued: type=${event.type} id=${event.taskId} key=${event.eventKey}")
            // 前台抑制移到消费阶段：发送后立即切桌面时，事件往往恰好在
            // onPause 前后被解析。若入队前就按“当前仍在前台”丢弃，会出现
            // 切到桌面后永远收不到；延迟消费也给生命周期切换留出时间。
            drainAsync(appContext, FOREGROUND_CHECK_DELAY_MS)
        } else {
            Log.d(TAG, "notification replay ignored: type=${event.type} id=${event.taskId} key=${event.eventKey}")
        }
    }

    /** 应用或保活服务重建后恢复未完成消费；过期租约会自动重试。 */
    fun resumePending(context: Context) = drainAsync(context.applicationContext)

    private fun handleResolved(context: Context, event: TaskEventParser.TaskEvent) {
        val key = event.eventKey
        val resolvedAt = synchronized(recentRequestTimes) { recentRequestTimes[key] ?: 0L }
        if (System.currentTimeMillis() - resolvedAt < RESOLVE_COOLDOWN_MS) {
            Log.d(TAG, "resolved ignored during cooldown: id=" + event.taskId + " key=" + key)
            return
        }
        // 只清理与该交互严格对应的未投递记录。同一任务连续出现第二次审批时，
        // 旧 resolved 的 eventKey 与新请求不同，绝不会误伤新通知。
        if (key.isNotEmpty()) {
            inbox(context).discardPendingInteraction(key)
        }
        if (event.taskId.isNotEmpty()) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(event.taskId.hashCode())
        }
    }

    private fun drainAsync(context: Context, delayMs: Long = 0L) {
        synchronized(this) {
            if (consumerRunning) return
            consumerRunning = true
        }
        executor.schedule({ drain(context) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun drain(context: Context) {
        try {
            while (true) {
                val record = inbox(context).claimNext() ?: return
                if (!consumeRecord(record, context)) return
            }
        } finally {
            synchronized(this) { consumerRunning = false }
            // 先释放运行标记再安排下一次领取，避免生产者刚入队时恰好错过消费者。
            inbox(context).nextWakeDelayMs()?.let { delay ->
                executor.schedule({ drainAsync(context) }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** 投递单条记录；返回 false 表示需要把记录留到下一轮（失败/租约到期）。 */
    private fun consumeRecord(
        record: TaskNotificationInbox.Record,
        context: Context,
    ): Boolean {
        return try {
            when (post(record, context)) {
                ConsumeResult.DELIVERED -> {
                    inbox(context).markDelivered(record.rowId)
                    true
                }
                ConsumeResult.DISCARDED -> {
                    inbox(context).markDiscarded(record.rowId)
                    true
                }
                ConsumeResult.RETRY -> {
                    inbox(context).markRetry(record.rowId, RETRY_DELAY_MS)
                    false
                }
            }
        } catch (e: Exception) {
            // 单个坏事件不能让整个消费者退出；回滚到待消费状态后继续处理下一条。
            Log.e(TAG, "notification consume failed; will retry: key=${record.event.eventKey}", e)
            inbox(context).markRetry(record.rowId, RETRY_DELAY_MS)
            false
        }
    }

    private fun post(record: TaskNotificationInbox.Record, context: Context): ConsumeResult {
        val event = record.event
        // 用户正停留在某任务会话页时，该会话自身的消息（审批/提问/完成/失败）
        // 已在页面上实时可见，弹系统通知属于打扰 → 抑制；其他会话的消息不受
        // 影响照常通知。RESOLVED 不经过这里（notify 入口已分流）。
        if (event.taskId.isNotEmpty() &&
            RemoteControlActivity.isForegroundSession(event.taskId)
        ) return ConsumeResult.DISCARDED
        val settings = AppSettingsRepository.getInstance(context)
        if (!settings.isNotificationEnabled() || !isEnabledForType(settings, event.type)) {
            Log.d(TAG, "notification disabled; event consumed: type=${event.type} id=${event.taskId}")
            return ConsumeResult.DISCARDED
        }
        ensureChannels(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "system notifications disabled; event consumed: id=${event.taskId}")
            return ConsumeResult.DISCARDED
        }
        val channel = channelFor(event)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 &&
            manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return ConsumeResult.DISCARDED

        val (title, text) = notificationText(context, event)
        val intent = PendingIntent.getActivity(
            context, (event.deviceName + ":" + event.taskId).hashCode(),
            buildLaunchIntent(context, event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        return try {
            // 审批/提问使用 taskId 作为通知 ID：同一任务的新交互替换旧横幅，避免堆积。
            // 完成/失败必须使用稳定的 eventKey 作为 ID：这些事件没有 pending 交互，
            // 若统一退化为 "general"，多个后台任务的完成/失败会互相 cancel，最终
            // 永远只留下最后一条 —— 表现为“失败/完成通知几次后就没了”。
            val id = if (isInteraction(event)) {
                event.taskId.ifEmpty { "general" }.hashCode()
            } else {
                event.eventKey.ifEmpty { event.taskId }.hashCode()
            }
            if (isInteraction(event)) manager.cancel(id)
            manager.notify(id, notification)
            if (isInteraction(event) && event.eventKey.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val key = event.eventKey
                synchronized(recentRequestKeys) {
                    recentRequestKeys[event.taskId] = key
                    if (recentRequestKeys.size > MAX_RECENT_REQUESTS) {
                        val oldest = recentRequestKeys.entries.minByOrNull { it.value }?.key
                        if (oldest != null) recentRequestKeys.remove(oldest)
                    }
                }
                synchronized(recentRequestTimes) {
                    recentRequestTimes[key] = now
                    if (recentRequestTimes.size > MAX_RECENT_REQUESTS) {
                        val oldestKey = recentRequestTimes.entries.minByOrNull { it.value }?.key
                        if (oldestKey != null) recentRequestTimes.remove(oldestKey)
                    }
                }
            }
            Log.i(TAG, "notification posted: type=${event.type} id=${event.taskId} key=${event.eventKey}")
            ConsumeResult.DELIVERED
        } catch (e: Exception) {
            Log.e(TAG, "notification post failed; will retry: type=${event.type} id=${event.taskId}", e)
            ConsumeResult.RETRY
        }
    }

    private fun isNotifiable(type: TaskEventParser.TaskEvent.Type) =
        type != TaskEventParser.TaskEvent.Type.RESOLVED

    private fun isInteraction(event: TaskEventParser.TaskEvent) =
        event.type == TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST ||
            event.type == TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST

    private fun isEnabledForType(settings: AppSettingsRepository, type: TaskEventParser.TaskEvent.Type) = when (type) {
        TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST -> settings.isNotifApprovalEnabled()
        TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST -> settings.isNotifElicitationEnabled()
        TaskEventParser.TaskEvent.Type.TASK_COMPLETED -> settings.isNotifCompletedEnabled()
        TaskEventParser.TaskEvent.Type.TASK_FAILED -> settings.isNotifFailedEnabled()
        TaskEventParser.TaskEvent.Type.RESOLVED -> false
    }

    private fun channelFor(event: TaskEventParser.TaskEvent) =
        if (isInteraction(event)) CHANNEL_APPROVALS else CHANNEL_EVENTS

    private fun notificationText(context: Context, event: TaskEventParser.TaskEvent): Pair<String, String> {
        val titleRes = when (event.type) {
            TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST -> R.string.notif_approval_title
            TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST -> R.string.notif_elicitation_title
            TaskEventParser.TaskEvent.Type.TASK_COMPLETED -> R.string.notif_completed_title
            TaskEventParser.TaskEvent.Type.TASK_FAILED -> R.string.notif_failed_title
            TaskEventParser.TaskEvent.Type.RESOLVED -> return "" to ""
        }
        val textRes = when (event.type) {
            TaskEventParser.TaskEvent.Type.PERMISSION_REQUEST -> R.string.notif_approval_text
            TaskEventParser.TaskEvent.Type.ELICITATION_REQUEST -> R.string.notif_elicitation_text
            TaskEventParser.TaskEvent.Type.TASK_COMPLETED -> R.string.notif_completed_text
            TaskEventParser.TaskEvent.Type.TASK_FAILED -> R.string.notif_failed_text
            TaskEventParser.TaskEvent.Type.RESOLVED -> return "" to ""
        }
        val task = event.taskTitle.ifBlank { context.getString(R.string.notif_task_unnamed) }
        val text = context.getString(textRes, task).let {
            if (event.deviceName.isBlank()) it else "$it · ${event.deviceName}"
        }
        return context.getString(titleRes) to text
    }

    private fun buildLaunchIntent(context: Context, event: TaskEventParser.TaskEvent): Intent {
        val connection = findConnection(context, event.deviceName)
        return if (connection != null) {
            Intent(context, RemoteControlActivity::class.java).apply {
                putExtra(RemoteControlActivity.EXTRA_URL, connection.url)
                putExtra(RemoteControlActivity.EXTRA_NAME, connection.name)
                if (event.taskId.isNotEmpty()) putExtra(RemoteControlActivity.EXTRA_TASK_ID, event.taskId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    private fun findConnection(context: Context, deviceName: String) = try {
        if (deviceName.isBlank()) null else ConnectionRepository.getInstance(context)
            .getAllConnections().firstOrNull { it.name == deviceName }
    } catch (_: Exception) { null }

    private fun inbox(context: Context): TaskNotificationInbox {
        inboxInstance?.let { return it }
        return synchronized(this) {
            inboxInstance ?: TaskNotificationInbox(context).also { inboxInstance = it }
        }
    }

    private enum class ConsumeResult { DELIVERED, DISCARDED, RETRY }
}
