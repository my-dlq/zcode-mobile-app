package ai.zcode.remote.ui.remote.event

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderProfile
import ai.zcode.remote.data.model.TaskReminderSound
import ai.zcode.remote.data.model.TaskReminderTone
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.annotation.RequiresApi
import java.security.MessageDigest

/** 每类任务通知独立配置。沿用旧版已有的设置，避免覆盖升级后意外响铃。 */
internal object TaskReminderChannels {
    private const val LEGACY_SHARED = "zcode_task_reminder_shared"
    private const val LEGACY_APPROVALS = "zcode_task_approvals"
    private const val LEGACY_EVENTS = "zcode_task_events"

    fun channelId(type: TaskReminderType): String = "zcode_task_reminder_${type.key}"

    fun label(type: TaskReminderType): Int = when (type) {
        TaskReminderType.APPROVAL -> R.string.notif_settings_approval
        TaskReminderType.QUESTION -> R.string.notif_settings_elicitation
        TaskReminderType.COMPLETED -> R.string.notif_settings_completed
        TaskReminderType.FAILED -> R.string.notif_settings_failed
    }

    @RequiresApi(26)
    fun current(context: Context, type: TaskReminderType): NotificationChannel? {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.getNotificationChannel(channelId(type)) ?: inheritedChannel(context, type, manager)
    }

    @RequiresApi(26)
    fun ensure(context: Context, type: TaskReminderType): String {
        val id = channelId(type)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) != null) return id

        val previousChannel = inheritedChannel(context, type, manager)
        val label = context.getString(label(type))
        // 0.3.5 的两个渠道各保留给原来的一种事件，另一种事件从原设置初始化。
        // 删除旧渠道会让系统显示删除计数，因此不清理已注册的渠道。
        if (previousChannel?.id == legacyId(type) &&
            (type == TaskReminderType.APPROVAL || type == TaskReminderType.COMPLETED)
        ) {
            if (previousChannel.name.toString() != label || previousChannel.description != null) {
                previousChannel.name = label
                previousChannel.description = null
                manager.createNotificationChannel(previousChannel)
            }
            return previousChannel.id
        }

        val defaultImportance = if (isInteraction(type)) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(NotificationChannel(id, label, previousChannel?.importance ?: defaultImportance).apply {
            if (previousChannel != null) {
                setSound(previousChannel.sound, previousChannel.audioAttributes)
                previousChannel.vibrationPattern?.let(::setVibrationPattern)
                enableVibration(previousChannel.shouldVibrate())
            } else {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), notificationAudioAttributes())
                enableVibration(true)
            }
        })
        return id
    }

    @RequiresApi(26)
    fun ensurePreset(
        context: Context,
        type: TaskReminderType,
        base: NotificationChannel,
        profile: TaskReminderProfile,
    ): String {
        val sound = profile.soundUri(context, base.sound)
        if (sound == base.sound && profile.vibrationEnabled == base.shouldVibrate()) return base.id

        // 每类通知和固定预设一一对应，切回旧预设时可以复用系统里已有的类别。
        val toneKey = if (profile.soundEnabled) {
            when (val tone = profile.tone) {
                is TaskReminderTone -> tone.key
                is TaskReminderSound.SystemTone -> "system_" + MessageDigest.getInstance("SHA-256")
                    .digest(tone.source.toString().toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            }
        } else {
            "no_sound"
        }
        val id = "zcode_task_preset_${type.key}_${toneKey}_${profile.vibrationEnabled}"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) != null) return id

        val toneLabel = if (profile.soundEnabled) profile.tone.title(context) else context.getString(R.string.notif_reminder_no_sound)
        val vibrationLabel = if (profile.vibrationEnabled) R.string.notif_reminder_vibration else R.string.notif_reminder_vibration_off
        val name = context.getString(
            R.string.notif_reminder_channel_name,
            context.getString(label(type)), toneLabel, context.getString(vibrationLabel),
        )
        manager.createNotificationChannel(NotificationChannel(id, name, base.importance).apply {
            setSound(sound, base.audioAttributes ?: notificationAudioAttributes())
            if (profile.vibrationEnabled) base.vibrationPattern?.let(::setVibrationPattern)
            enableVibration(profile.vibrationEnabled)
            setShowBadge(base.canShowBadge())
            lockscreenVisibility = base.lockscreenVisibility
        })
        return id
    }

    @RequiresApi(26)
    private fun inheritedChannel(context: Context, type: TaskReminderType, manager: NotificationManager): NotificationChannel? =
        when (AppSettingsRepository.getInstance(context).previousTaskReminderMode(type)) {
            "CUSTOM" -> null
            "SHARED" -> manager.getNotificationChannel(LEGACY_SHARED)
            else -> manager.getNotificationChannel(legacyId(type)) ?: manager.getNotificationChannel(LEGACY_SHARED)
        }

    private fun legacyId(type: TaskReminderType): String = if (isInteraction(type)) LEGACY_APPROVALS else LEGACY_EVENTS

    private fun notificationAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()

    private fun isInteraction(type: TaskReminderType): Boolean =
        type == TaskReminderType.APPROVAL || type == TaskReminderType.QUESTION
}
