package ai.zcode.remote.ui.settings

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderMode
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityNotificationSettingsBinding
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
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NotificationSettingsActivity : BaseActivity() {
    private lateinit var binding: ActivityNotificationSettingsBinding
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
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)
        val savedType = savedInstanceState?.getString(STATE_TONE_TYPE)
        pendingToneType = TaskReminderType.entries.firstOrNull { it.key == savedType }

        binding.btnBack.setOnClickListener { finish() }
        binding.sharedReminder.title.setText(R.string.notif_reminder_shared)
        binding.sharedReminder.root.setOnClickListener { configureReminder() }

        binding.switchNotifMaster.isChecked = settings.isNotificationEnabled()
        binding.rowNotifMaster.setOnClickListener { binding.switchNotifMaster.toggle() }
        binding.switchNotifMaster.setOnCheckedChangeListener { _, enabled ->
            settings.setNotificationEnabled(enabled)
            updateReceiveSwitches(enabled)
        }
        bindEventRow(TaskReminderType.APPROVAL, settings.isNotifApprovalEnabled(), settings::setNotifApprovalEnabled)
        bindEventRow(TaskReminderType.QUESTION, settings.isNotifElicitationEnabled(), settings::setNotifElicitationEnabled)
        bindEventRow(TaskReminderType.COMPLETED, settings.isNotifCompletedEnabled(), settings::setNotifCompletedEnabled)
        bindEventRow(TaskReminderType.FAILED, settings.isNotifFailedEnabled(), settings::setNotifFailedEnabled)
        updateReceiveSwitches(settings.isNotificationEnabled())
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

    private fun bindEventRow(type: TaskReminderType, enabled: Boolean, onEnabledChanged: (Boolean) -> Unit) {
        val row = ItemNotificationReminderBinding.inflate(layoutInflater, binding.eventRows, true)
        val title = getString(TaskReminderChannels.label(type))
        row.title.text = title
        row.root.setOnClickListener { configureReminder(type, TaskReminderChannels.mode(this, type)) }
        row.chevron.isVisible = false
        row.modeButton.isVisible = true
        row.modeButton.contentDescription = getString(R.string.notif_reminder_mode_action, title)
        row.modeButton.setOnClickListener { chooseMode(type) }
        row.receiveSwitch.isVisible = true
        row.receiveSwitch.contentDescription = getString(R.string.notif_reminder_receive_action, title)
        row.receiveSwitch.isChecked = enabled
        row.receiveSwitch.setOnCheckedChangeListener { _, checked -> onEnabledChanged(checked) }
        eventRows[type] = row
    }

    private fun updateReceiveSwitches(masterEnabled: Boolean) {
        eventRows.values.forEach { it.receiveSwitch.isEnabled = masterEnabled }
    }

    private fun chooseMode(type: TaskReminderType) {
        val options = buildList {
            add(TaskReminderMode.SHARED)
            add(TaskReminderMode.CUSTOM)
            if (TaskReminderChannels.hasLegacyChannel(this@NotificationSettingsActivity, type)) {
                add(TaskReminderMode.LEGACY)
            }
        }
        val selected = options.indexOf(TaskReminderChannels.mode(this, type))
        MaterialAlertDialogBuilder(this)
            .setTitle(TaskReminderChannels.label(type))
            .setSingleChoiceItems(options.map { getString(modeLabel(it)) }.toTypedArray(), selected) { dialog, index ->
                val mode = options[index]
                settings.setTaskReminderMode(type, mode)
                dialog.dismiss()
                refreshState()
                if (mode == TaskReminderMode.CUSTOM) configureReminder(type, mode)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun configureReminder(type: TaskReminderType? = null, mode: TaskReminderMode = TaskReminderMode.SHARED) {
        if (Build.VERSION.SDK_INT < 26) {
            configureLegacyDevice(type.takeIf { mode == TaskReminderMode.CUSTOM })
            return
        }
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
            context.startActivity(Intent(context, NotificationSettingsActivity::class.java))
        }
    }
}
