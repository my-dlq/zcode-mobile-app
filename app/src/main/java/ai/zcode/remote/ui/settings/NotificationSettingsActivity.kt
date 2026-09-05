package ai.zcode.remote.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityNotificationSettingsBinding

/**
 * 通知管理设置页：总开关 + 各事件类型独立开关。
 * 偏好存 AppSettingsRepository，TaskNotifier 发布前逐条检查。
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding
    private lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }

        // 行点击 = 开关切换
        binding.rowNotifMaster.setOnClickListener { binding.switchNotifMaster.toggle() }
        binding.rowNotifApproval.setOnClickListener { binding.switchNotifApproval.toggle() }
        binding.rowNotifElicitation.setOnClickListener { binding.switchNotifElicitation.toggle() }
        binding.rowNotifCompleted.setOnClickListener { binding.switchNotifCompleted.toggle() }
        binding.rowNotifFailed.setOnClickListener { binding.switchNotifFailed.toggle() }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::appSettings.isInitialized) {
            refreshState()
        }
    }

    private fun refreshState() {
        // 总开关
        binding.switchNotifMaster.setOnCheckedChangeListener(null)
        binding.switchNotifMaster.isChecked = appSettings.isNotificationEnabled()
        binding.switchNotifMaster.setOnCheckedChangeListener { _, checked ->
            appSettings.setNotificationEnabled(checked)
            updateEventRowsEnabled(checked)
        }
        updateEventRowsEnabled(appSettings.isNotificationEnabled())

        // 各事件类型
        binding.switchNotifApproval.setOnCheckedChangeListener(null)
        binding.switchNotifApproval.isChecked = appSettings.isNotifApprovalEnabled()
        binding.switchNotifApproval.setOnCheckedChangeListener { _, checked ->
            appSettings.setNotifApprovalEnabled(checked)
        }

        binding.switchNotifElicitation.setOnCheckedChangeListener(null)
        binding.switchNotifElicitation.isChecked = appSettings.isNotifElicitationEnabled()
        binding.switchNotifElicitation.setOnCheckedChangeListener { _, checked ->
            appSettings.setNotifElicitationEnabled(checked)
        }

        binding.switchNotifCompleted.setOnCheckedChangeListener(null)
        binding.switchNotifCompleted.isChecked = appSettings.isNotifCompletedEnabled()
        binding.switchNotifCompleted.setOnCheckedChangeListener { _, checked ->
            appSettings.setNotifCompletedEnabled(checked)
        }

        binding.switchNotifFailed.setOnCheckedChangeListener(null)
        binding.switchNotifFailed.isChecked = appSettings.isNotifFailedEnabled()
        binding.switchNotifFailed.setOnCheckedChangeListener { _, checked ->
            appSettings.setNotifFailedEnabled(checked)
        }
    }

    /** 总开关关闭时禁用事件类型行（视觉半透明 + 不可点击）。 */
    private fun updateEventRowsEnabled(masterEnabled: Boolean) {
        val alpha = if (masterEnabled) 1.0f else 0.4f
        binding.rowNotifApproval.alpha = alpha
        binding.rowNotifElicitation.alpha = alpha
        binding.rowNotifCompleted.alpha = alpha
        binding.rowNotifFailed.alpha = alpha
        binding.switchNotifApproval.isEnabled = masterEnabled
        binding.switchNotifElicitation.isEnabled = masterEnabled
        binding.switchNotifCompleted.isEnabled = masterEnabled
        binding.switchNotifFailed.isEnabled = masterEnabled
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NotificationSettingsActivity::class.java))
        }
    }
}
