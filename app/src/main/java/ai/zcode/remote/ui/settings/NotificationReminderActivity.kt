package ai.zcode.remote.ui.settings

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderProfile
import ai.zcode.remote.data.model.TaskReminderTone
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityNotificationReminderBinding
import ai.zcode.remote.databinding.ItemNotificationReminderCategoryBinding
import ai.zcode.remote.ui.BaseActivity
import ai.zcode.remote.ui.remote.event.TaskReminderChannels
import ai.zcode.remote.utils.TaskReminderVibration
import ai.zcode.remote.utils.ToastUtils
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.view.isVisible

class NotificationReminderActivity : BaseActivity() {
    private lateinit var binding: ActivityNotificationReminderBinding
    private lateinit var settings: AppSettingsRepository
    private val rows = mutableMapOf<TaskReminderType, ItemNotificationReminderCategoryBinding>()
    private val originalToneNames = mutableMapOf<Uri, String>()
    private var expanded: TaskReminderType? = TaskReminderType.APPROVAL
    private var preview: Ringtone? = null
    private var previewUri: Uri? = null
    private var picker: NotificationTonePicker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        settings = AppSettingsRepository.getInstance(this)
        binding.alertMasterSwitch.isChecked = settings.areTaskReminderAlertsEnabled()
        binding.alertMasterSwitch.setOnCheckedChangeListener { _, enabled ->
            stopPreview()
            settings.setTaskReminderAlertsEnabled(enabled)
            render()
        }

        if (savedInstanceState?.containsKey(STATE_EXPANDED) == true) {
            expanded = TaskReminderType.entries.firstOrNull { it.key == savedInstanceState.getString(STATE_EXPANDED) }
        }
        for (type in TaskReminderType.entries) {
            val row = ItemNotificationReminderCategoryBinding.inflate(layoutInflater, binding.eventRows, true)
            rows[type] = row
            row.eventTitle.setText(TaskReminderChannels.label(type))
            row.categoryHeader.setOnClickListener {
                stopPreview()
                expanded = if (expanded == type) null else type
                render()
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXPANDED, expanded?.key)
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    override fun onDestroy() {
        picker?.dismiss()
        super.onDestroy()
    }

    private fun render() {
        val alertsEnabled = settings.areTaskReminderAlertsEnabled()
        for ((type, row) in rows) {
            val saved = settings.taskReminderProfile(type)
            val isExpanded = expanded == type
            row.eventStatus.setText(when {
                isExpanded && !alertsEnabled -> R.string.notif_reminder_master_muted
                saved == null -> R.string.notif_reminder_follow_existing
                saved.isSilent -> R.string.notif_reminder_silent_summary
                saved.soundEnabled && saved.vibrationEnabled -> R.string.notif_reminder_mode_both
                saved.soundEnabled -> R.string.notif_reminder_mode_sound_only
                else -> R.string.notif_reminder_mode_vibration_only
            })
            row.categoryControls.isVisible = isExpanded
            row.categoryArrow.rotation = if (isExpanded) 90f else 0f
            row.categoryHeader.contentDescription = getString(
                if (isExpanded) R.string.notif_reminder_category_collapse else R.string.notif_reminder_category_expand,
                row.eventTitle.text, row.eventStatus.text,
            )
            if (isExpanded) bindControls(type, row, saved, alertsEnabled)
        }
    }

    private fun bindControls(
        type: TaskReminderType,
        row: ItemNotificationReminderCategoryBinding,
        saved: TaskReminderProfile?,
        alertsEnabled: Boolean,
    ) {
        val (originalSound, originalVibration) = originalAlert(type)
        val profile = saved ?: TaskReminderProfile(originalSound != null, originalVibration)
        row.soundSwitch.setOnCheckedChangeListener(null)
        row.vibrationSwitch.setOnCheckedChangeListener(null)
        row.soundSwitch.isChecked = profile.soundEnabled
        row.vibrationSwitch.isChecked = profile.vibrationEnabled
        row.soundSwitch.isEnabled = alertsEnabled
        row.vibrationSwitch.isEnabled = alertsEnabled
        row.soundSwitch.setOnCheckedChangeListener { _, enabled -> save(type, profile.copy(soundEnabled = enabled)) }
        row.vibrationSwitch.setOnCheckedChangeListener { _, enabled ->
            save(type, profile.copy(vibrationEnabled = enabled))
            if (enabled) TaskReminderVibration.preview(this)
        }

        val inheritedSound = originalSound?.takeUnless(RingtoneManager::isDefault)
        val selected = if (profile.tone == TaskReminderTone.ORIGINAL && inheritedSound == null) {
            TaskReminderTone.DEFAULT
        } else {
            profile.tone
        }
        row.toneChoice.isVisible = profile.soundEnabled
        row.toneChoice.isEnabled = alertsEnabled
        if (profile.soundEnabled) {
            row.toneName.text = if (selected == TaskReminderTone.ORIGINAL && inheritedSound != null) {
                originalToneName(inheritedSound)
            } else {
                selected.title(this)
            }
            row.toneChoice.contentDescription = getString(R.string.notif_reminder_choose_tone, row.toneName.text)
        }
        row.toneChoice.setOnClickListener {
            stopPreview()
            picker = NotificationTonePicker(
                this, selected,
                inheritedSound = inheritedSound,
                onPreview = { tone -> playPreview(tone.uri(this, originalSound)) },
                onSelected = { tone -> save(type, profile.copy(tone = tone)) },
            ).apply {
                setOnDismissListener {
                    stopPreview()
                    picker = null
                }
                show()
            }
        }
        row.reset.isVisible = saved != null
        row.reset.isEnabled = alertsEnabled
        row.reset.setOnClickListener { save(type, null) }
    }

    private fun originalToneName(uri: Uri): String = originalToneNames.getOrPut(uri) {
        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.Audio.AudioColumns.TITLE), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.notif_reminder_current_system_tone)
    }

    private fun originalAlert(type: TaskReminderType): Pair<Uri?, Boolean> {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = TaskReminderChannels.current(this, type)
            if (channel != null) return channel.sound to channel.shouldVibrate()
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) to true
        }
        return settings.getTaskReminderTone(type).takeIf { it.isNotEmpty() }?.let(Uri::parse) to
            settings.isTaskReminderVibrationEnabled(type)
    }

    private fun save(type: TaskReminderType, profile: TaskReminderProfile?) {
        stopPreview()
        settings.setTaskReminderProfile(type, profile)
        render()
    }

    private fun playPreview(uri: Uri) {
        val alreadyPlaying = previewUri == uri && preview?.isPlaying == true
        stopPreview()
        if (alreadyPlaying) return
        runCatching {
            val ringtone = checkNotNull(RingtoneManager.getRingtone(this, uri))
            // 试听走当前媒体输出，系统通知仍由其通知渠道播放。
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            preview = ringtone
            previewUri = uri
            ringtone.play()
        }.onFailure { ToastUtils.show(this, getString(R.string.notif_reminder_preview_unavailable)) }
    }

    private fun stopPreview() {
        preview?.stop()
        preview = null
        previewUri = null
    }

    companion object {
        private const val STATE_EXPANDED = "expanded_reminder"

        fun start(context: Context) {
            context.startActivity(Intent(context, NotificationReminderActivity::class.java))
        }
    }
}
