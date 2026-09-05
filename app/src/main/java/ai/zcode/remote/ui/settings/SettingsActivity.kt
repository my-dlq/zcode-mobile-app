
package ai.zcode.remote.ui.settings

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.UpdateRepository
import ai.zcode.remote.databinding.ActivitySettingsBinding
import ai.zcode.remote.service.KeepAliveService
import ai.zcode.remote.ui.security.SecuritySettingsActivity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * 应用设置页：集中管理原生客户端的显示、通用、安全主题和更新偏好。
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
        binding.rowBattery.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            appSettings.setKeepAliveEnabled(checked)
            if (checked) KeepAliveService.start(this) else KeepAliveService.stop(this)
            refreshKeepAliveState()
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
        refreshKeepAliveState()
    }

    private fun refreshKeepAliveState() {
        val enabled = appSettings.isKeepAliveEnabled()
        binding.switchKeepAlive.setOnCheckedChangeListener(null)
        binding.switchKeepAlive.isChecked = enabled
        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            appSettings.setKeepAliveEnabled(checked)
            if (checked) KeepAliveService.start(this) else KeepAliveService.stop(this)
            refreshKeepAliveState()
        }
        binding.tvKeepAliveSummary.setText(
            if (enabled) R.string.settings_keepalive_summary_on
            else R.string.settings_keepalive_summary_off
        )
        // 保活开启且系统未忽略电池优化时，显示引导行（MIUI 等国产 ROM 会激进回收后台）
        val ignoring = isIgnoringBatteryOptimizations()
        binding.rowBattery.visibility = if (enabled && !ignoring) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvBatteryGuide.setText(
            if (ignoring) R.string.settings_keepalive_battery_ok
            else R.string.settings_keepalive_battery_guide
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        try {
            // 优先跳应用详情页（各 ROM 通用，可从"电池"项进入具体设置）
            val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(detail)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // 无对应页面时静默忽略
            }
        }
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
