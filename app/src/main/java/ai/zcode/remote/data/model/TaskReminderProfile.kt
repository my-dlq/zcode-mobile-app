package ai.zcode.remote.data.model

import ai.zcode.remote.R
import android.content.ContentResolver
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

internal sealed interface TaskReminderSound {
    fun title(context: Context): String
    fun uri(context: Context, originalSound: Uri?): Uri

    data class SystemTone(val source: Uri, val name: String) : TaskReminderSound {
        override fun title(context: Context): String = name
        override fun uri(context: Context, originalSound: Uri?): Uri = source
    }
}

internal enum class TaskReminderTone(val key: String, private val label: Int, private val sound: Int? = null) : TaskReminderSound {
    ORIGINAL("original", R.string.notif_reminder_tone_original),
    DEFAULT("default", R.string.notif_reminder_tone_default),
    CHIME("chime", R.string.notif_reminder_tone_chime, R.raw.reminder_chime),
    PULSE("pulse", R.string.notif_reminder_tone_pulse, R.raw.reminder_pulse);

    override fun title(context: Context): String = context.getString(label)

    override fun uri(context: Context, originalSound: Uri?): Uri {
        if (this == ORIGINAL && originalSound != null) return originalSound
        return sound?.let {
            // 使用资源名，已保存通知类别无需依赖升级时可能变化的数字资源 ID。
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.packageName).appendPath("raw")
                .appendPath(context.resources.getResourceEntryName(it)).build()
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }
}

internal data class TaskReminderProfile(
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val tone: TaskReminderSound = TaskReminderTone.ORIGINAL,
) {
    val isSilent: Boolean get() = !soundEnabled && !vibrationEnabled

    fun soundUri(context: Context, originalSound: Uri?): Uri? =
        if (soundEnabled) tone.uri(context, originalSound) else null
}
