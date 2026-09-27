
package ai.zcode.remote.ui.settings

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityThemeSettingsBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import ai.zcode.remote.utils.ThemeHelper

/** 主题选择页：主题使用同一个持久化偏好并立即应用。 */
class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeSettingsBinding
    private lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用用户选择的主题（亮色/暗色/石墨灰）；石墨灰需在 super.onCreate 前 setTheme
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowLight.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.LIGHT) }
        binding.rowDark.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.DARK) }
        binding.rowGraphite.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.GRAPHITE) }
        binding.radioLight.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.LIGHT) }
        binding.radioDark.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.DARK) }
        binding.radioGraphite.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.GRAPHITE) }

        refreshSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::appSettings.isInitialized) {
            refreshSelection()
        }
    }

    private fun refreshSelection() {
        val mode = appSettings.getThemeMode()
        binding.radioLight.isChecked = mode == AppSettingsRepository.ThemeMode.LIGHT
        binding.radioDark.isChecked = mode == AppSettingsRepository.ThemeMode.DARK
        binding.radioGraphite.isChecked = mode == AppSettingsRepository.ThemeMode.GRAPHITE
    }

    private fun selectTheme(mode: AppSettingsRepository.ThemeMode) {
        appSettings.setThemeMode(mode)
        // 夜间模式只表达"亮/非亮"；石墨灰靠 ThemeHelper 在 onCreate 前 setTheme 落地。
        AppCompatDelegate.setDefaultNightMode(ThemeHelper.nightModeFor(mode))
        // 立刻在当前 Activity 上生效（夜间模式对已创建 Activity 的重建是异步的，
        // 且石墨灰的 setTheme 只在 onCreate 生效，故显式重建一次拿到正确配色）
        recreate()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
        }
    }
}
