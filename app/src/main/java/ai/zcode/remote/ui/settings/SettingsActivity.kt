
package ai.zcode.remote.ui.settings

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.UpdateRepository
import ai.zcode.remote.databinding.ActivitySettingsBinding
import ai.zcode.remote.ui.security.SecuritySettingsActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 应用设置页：集中管理原生客户端的显示、主题和更新偏好。
 * 远程工作区自身的设置仍由远程控制页中的「远程设置」入口负责。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appSettings: AppSettingsRepository
    private lateinit var updateSettings: UpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)
        updateSettings = UpdateRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowFullscreen.setOnClickListener {
            binding.switchFullscreen.toggle()
        }
        binding.rowTheme.setOnClickListener {
            ThemeSettingsActivity.start(this)
        }
        binding.rowSecurity.setOnClickListener {
            SecuritySettingsActivity.start(this)
        }
        binding.rowUpdate.setOnClickListener {
            binding.switchAutoCheckUpdate.toggle()
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::appSettings.isInitialized) {
            refreshState()
        }
    }

    private fun refreshState() {
        val fullscreenEnabled = appSettings.isFullscreenEnabled()
        binding.switchFullscreen.setOnCheckedChangeListener(null)
        binding.switchFullscreen.isChecked = fullscreenEnabled
        binding.switchFullscreen.setOnCheckedChangeListener { _, checked ->
            appSettings.setFullscreenEnabled(checked)
            updateFullscreenSummary(checked)
        }
        updateFullscreenSummary(fullscreenEnabled)

        val autoCheckEnabled = updateSettings.isAutoCheckEnabled()
        binding.switchAutoCheckUpdate.setOnCheckedChangeListener(null)
        binding.switchAutoCheckUpdate.isChecked = autoCheckEnabled
        binding.switchAutoCheckUpdate.setOnCheckedChangeListener { _, checked ->
            updateSettings.setAutoCheckEnabled(checked)
        }

        binding.tvThemeValue.text = when (appSettings.getThemeMode()) {
            AppSettingsRepository.ThemeMode.DARK -> getString(R.string.settings_theme_dark)
            AppSettingsRepository.ThemeMode.LIGHT -> getString(R.string.settings_theme_light)
        }
        binding.tvSecuritySummary.setText(
            if (appSettings.isSecurityVerificationEnabled()) {
                R.string.settings_security_summary_enabled
            } else {
                R.string.settings_security_summary_disabled
            }
        )
    }

    private fun updateFullscreenSummary(enabled: Boolean) {
        binding.tvFullscreenSummary.setText(
            if (enabled) R.string.settings_fullscreen_on else R.string.settings_fullscreen_off
        )
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
