package ai.zcode.remote.ui.settings

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderMode
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityNotificationReminderBinding
import ai.zcode.remote.databinding.ItemNotificationReminderBinding
import ai.zcode.remote.ui.BaseActivity
import ai.zcode.remote.ui.remote.event.TaskReminderChannels
import ai.zcode.remote.utils.ToastUtils
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NotificationReminderActivity : BaseActivity() {
    private lateinit var binding: ActivityNotificationReminderBinding
    private lateinit var settings: AppSettingsRepository
    private val eventRows = mutableMapOf<TaskReminderType, ItemNotificationReminderBinding>()
    private var pendingToneType: TaskReminderType? = null

    private val tonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            @Suppress("DEPRECATION") // 此选择器只用于 Android 7。
            val tone = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            settings.setTaskReminderTone(pendingToneType, tone?.toString().orEmpty())
            refreshState()
        }
        pendingToneType = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)
        val savedType = savedInstanceState?.getString(STATE_TONE_TYPE)
        pendingToneType = TaskReminderType.entries.firstOrNull { it.key == savedType }

        binding.btnBack.setOnClickListener { finish() }
        binding.sharedReminder.title.setText(R.string.notif_reminder_shared)
        binding.sharedReminder.root.setOnClickListener { configureReminder(null) }
        for (type in TaskReminderType.entries) {
            val row = ItemNotificationReminderBinding.inflate(layoutInflater, binding.eventRows, true)
            row.title.setText(TaskReminderChannels.label(type))
            row.root.setOnClickListener { chooseMode(type) }
            eventRows[type] = row
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TONE_TYPE, pendingToneType?.key)
    }

    private fun chooseMode(type: TaskReminderType) {
        val options = buildList {
            add(TaskReminderMode.SHARED)
            add(TaskReminderMode.CUSTOM)
            if (TaskReminderChannels.hasLegacyChannel(this@NotificationReminderActivity, type)) add(TaskReminderMode.LEGACY)
        }
        var selection = options.indexOf(TaskReminderChannels.mode(this, type))
        MaterialAlertDialogBuilder(this)
            .setTitle(TaskReminderChannels.label(type))
            .setSingleChoiceItems(options.map { getString(modeLabel(it)) }.toTypedArray(), selection) { _, index ->
                selection = index
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val mode = options[selection]
                settings.setTaskReminderMode(type, mode)
                refreshState()
                if (mode == TaskReminderMode.CUSTOM) configureReminder(type)
            }
            .show()
    }

    private fun configureReminder(type: TaskReminderType?) {
        if (Build.VERSION.SDK_INT < 26) {
            configureLegacyDevice(type)
            return
        }
        val mode = if (type == null) TaskReminderMode.SHARED else TaskReminderMode.CUSTOM
        val channelId = TaskReminderChannels.ensure(this, type, mode)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        if (!tryStartActivity(intent)) {
            val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (!tryStartActivity(appIntent)) ToastUtils.show(this, getString(R.string.notif_reminder_settings_unavailable))
        }
    }

    private fun configureLegacyDevice(type: TaskReminderType?) {
        val vibration = settings.isTaskReminderVibrationEnabled(type)
        val vibrationLabel = if (vibration) R.string.notif_reminder_disable_vibration else R.string.notif_reminder_enable_vibration
        MaterialAlertDialogBuilder(this)
            .setTitle(type?.let(TaskReminderChannels::label) ?: R.string.notif_reminder_shared)
            .setItems(arrayOf(getString(R.string.notif_reminder_select_tone), getString(vibrationLabel))) { _, index ->
                if (index == 0) {
                    selectTone(type)
                } else {
                    settings.setTaskReminderVibrationEnabled(type, !vibration)
                    refreshState()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun selectTone(type: TaskReminderType?) {
        val current = settings.getTaskReminderTone(type).takeIf { it.isNotEmpty() }?.let(Uri::parse)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        pendingToneType = type
        try {
            tonePicker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pendingToneType = null
            ToastUtils.show(this, getString(R.string.notif_reminder_settings_unavailable))
        }
    }

    private fun refreshState() {
        binding.sharedReminder.summary.text = reminderSummary(null, TaskReminderMode.SHARED)
        for ((type, row) in eventRows) {
            val mode = TaskReminderChannels.mode(this, type)
            row.summary.text = getString(R.string.notif_reminder_mode_summary, getString(modeLabel(mode)), reminderSummary(type, mode))
        }
    }

    private fun reminderSummary(type: TaskReminderType?, mode: TaskReminderMode): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val id = type?.let { TaskReminderChannels.channelId(it, mode) } ?: TaskReminderChannels.SHARED_ID
            val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(id)
                ?: return alertSummary(sound = true, vibration = true)
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return getString(R.string.notif_reminder_blocked)
            if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return alertSummary(sound = false, vibration = false)
            return alertSummary(channel.sound != null, channel.shouldVibrate())
        }
        val profile = type.takeIf { mode == TaskReminderMode.CUSTOM }
        return alertSummary(settings.getTaskReminderTone(profile).isNotEmpty(), settings.isTaskReminderVibrationEnabled(profile))
    }

    private fun alertSummary(sound: Boolean, vibration: Boolean): String = getString(when {
        sound && vibration -> R.string.notif_reminder_sound_and_vibration
        sound -> R.string.notif_reminder_sound_only
        vibration -> R.string.notif_reminder_vibration_only
        else -> R.string.notif_reminder_silent
    })

    private fun modeLabel(mode: TaskReminderMode): Int = when (mode) {
        TaskReminderMode.SHARED -> R.string.notif_reminder_follow_shared
        TaskReminderMode.CUSTOM -> R.string.notif_reminder_custom
        TaskReminderMode.LEGACY -> R.string.notif_reminder_legacy
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    companion object {
        private const val STATE_TONE_TYPE = "reminder_tone_type"

        fun start(context: Context) {
            context.startActivity(Intent(context, NotificationReminderActivity::class.java))
        }
    }
}
