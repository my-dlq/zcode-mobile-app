package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderMode
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.annotation.RequiresApi

/** 发布通知和设置页共用渠道选择，旧渠道保留系统原有设置。 */
internal object TaskReminderChannels {
    const val SHARED_ID = "zcode_task_reminder_shared"
    private const val LEGACY_APPROVALS = "zcode_task_approvals"
    private const val LEGACY_EVENTS = "zcode_task_events"

    fun mode(context: Context, type: TaskReminderType): TaskReminderMode {
        val saved = AppSettingsRepository.getInstance(context).getTaskReminderMode(type)
        if (saved == TaskReminderMode.CUSTOM || saved == TaskReminderMode.SHARED) return saved
        return if (hasLegacyChannel(context, type)) TaskReminderMode.LEGACY else TaskReminderMode.SHARED
    }

    fun hasLegacyChannel(context: Context, type: TaskReminderType): Boolean =
        Build.VERSION.SDK_INT >= 26 && notificationManager(context).getNotificationChannel(legacyId(type)) != null

    fun channelId(type: TaskReminderType, mode: TaskReminderMode): String = when (mode) {
        TaskReminderMode.LEGACY -> legacyId(type)
        TaskReminderMode.SHARED -> SHARED_ID
        TaskReminderMode.CUSTOM -> customId(type)
    }

    fun label(type: TaskReminderType): Int = when (type) {
        TaskReminderType.APPROVAL -> R.string.notif_settings_approval
        TaskReminderType.QUESTION -> R.string.notif_settings_elicitation
        TaskReminderType.COMPLETED -> R.string.notif_settings_completed
        TaskReminderType.FAILED -> R.string.notif_settings_failed
    }

    @RequiresApi(26)
    fun ensure(context: Context, type: TaskReminderType?, mode: TaskReminderMode): String {
        val id = if (type == null) SHARED_ID else channelId(type, mode)
        if (mode == TaskReminderMode.LEGACY) return id
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(id) != null) return id
        val customType = type?.takeIf { mode == TaskReminderMode.CUSTOM }
        val importance = if (customType != null && isInteraction(customType)) {
            NotificationManager.IMPORTANCE_HIGH
        } else {
            NotificationManager.IMPORTANCE_DEFAULT
        }
        val name = context.getString(customType?.let(::label) ?: R.string.notif_reminder_shared)
        manager.createNotificationChannel(NotificationChannel(id, name, importance).apply {
            if (customType == null) description = context.getString(R.string.notif_reminder_shared_description)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(true)
        })
        return id
    }

    fun isInteraction(type: TaskReminderType): Boolean =
        type == TaskReminderType.APPROVAL || type == TaskReminderType.QUESTION

    private fun legacyId(type: TaskReminderType): String =
        if (isInteraction(type)) LEGACY_APPROVALS else LEGACY_EVENTS

    private fun customId(type: TaskReminderType): String = "zcode_task_reminder_${type.key}"

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
