package ai.zcode.remote.ui.settings

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderType
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityNotificationSettingsBinding
import ai.zcode.remote.databinding.ItemNotificationReminderBinding
import ai.zcode.remote.ui.BaseActivity
import ai.zcode.remote.ui.remote.event.TaskReminderChannels
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible

class NotificationSettingsActivity : BaseActivity() {
    private lateinit var binding: ActivityNotificationSettingsBinding
    private lateinit var settings: AppSettingsRepository
    private val eventRows = mutableListOf<ItemNotificationReminderBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.reminderSettings.title.setText(R.string.notif_reminder_title)
        binding.reminderSettings.summary.setText(R.string.notif_reminder_entry_summary)
        binding.reminderSettings.root.setOnClickListener { NotificationReminderActivity.start(this) }

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
    }

    private fun bindEventRow(type: TaskReminderType, enabled: Boolean, onEnabledChanged: (Boolean) -> Unit) {
        val row = ItemNotificationReminderBinding.inflate(layoutInflater, binding.eventRows, true)
        val title = getString(TaskReminderChannels.label(type))
        row.title.text = title
        row.summary.isVisible = false
        row.chevron.isVisible = false
        row.root.setOnClickListener { row.receiveSwitch.toggle() }
        row.receiveSwitch.isVisible = true
        row.receiveSwitch.contentDescription = getString(R.string.notif_reminder_receive_action, title)
        row.receiveSwitch.isChecked = enabled
        row.receiveSwitch.setOnCheckedChangeListener { _, checked -> onEnabledChanged(checked) }
        eventRows += row
    }

    private fun updateReceiveSwitches(masterEnabled: Boolean) {
        for (row in eventRows) {
            row.root.isEnabled = masterEnabled
            row.receiveSwitch.isEnabled = masterEnabled
            row.root.alpha = if (masterEnabled) 1f else 0.4f
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NotificationSettingsActivity::class.java))
        }
    }
}
