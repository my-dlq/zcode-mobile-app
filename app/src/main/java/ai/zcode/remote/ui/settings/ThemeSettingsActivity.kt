
package ai.zcode.remote.ui.settings

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityThemeSettingsBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate

/** 主题选择页：主题使用同一个持久化偏好并立即应用。 */
class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeSettingsBinding
    private lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowLight.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.LIGHT) }
        binding.rowDark.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.DARK) }
        binding.radioLight.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.LIGHT) }
        binding.radioDark.setOnClickListener { selectTheme(AppSettingsRepository.ThemeMode.DARK) }

        refreshSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::appSettings.isInitialized) {
            refreshSelection()
        }
    }

    private fun refreshSelection() {
        when (appSettings.getThemeMode()) {
            AppSettingsRepository.ThemeMode.LIGHT -> {
                binding.radioLight.isChecked = true
                binding.radioDark.isChecked = false
            }
            AppSettingsRepository.ThemeMode.DARK -> {
                binding.radioLight.isChecked = false
                binding.radioDark.isChecked = true
            }
        }
    }

    private fun selectTheme(mode: AppSettingsRepository.ThemeMode) {
        appSettings.setThemeMode(mode)
        AppCompatDelegate.setDefaultNightMode(
            if (mode != AppSettingsRepository.ThemeMode.LIGHT) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        refreshSelection()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
        }
    }
}
