package ai.zcode.remote.ui.settings

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityLanguageSettingsBinding
import ai.zcode.remote.ui.BaseActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言选择页：跟随系统 / 中文 / English 三选。
 *
 * 走 AppCompat 1.7 官方 per-app language 通道：
 * - `AppCompatDelegate.setApplicationLocales()` 会自动持久化（Manifest 里挂了
 *   `AppLocalesMetadataHolderService` + `autoStoreLocales=true`）、自动重建任务栈、
 *   Android 13+ 自动同步到系统「应用语言」设置项。
 * - [AppSettingsRepository.setLanguage] 只是同步记录用户选择用于下次 UI 显示；
 *   真正生效的是 AppCompat 自己的持久化，两份数据保持一致即可。
 * - 选「跟随系统」时传 `LocaleListCompat.getEmptyLocaleList()`，AppCompat 会
 *   回落到系统语言；这也是首次启动未设置时的默认行为。
 *
 * 注意：远端 WebView 页（zcode.z.ai）语言不随本设置切换——
 * 远端由 `RemoteControlActivity` 在加载时显式锁定 Accept-Language 与
 * `navigator.language` 为中文，以保证注入脚本中的中文 aria-label 选择器稳定命中。
 */
class LanguageSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityLanguageSettingsBinding
    private lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowFollowSystem.setOnClickListener { selectLanguage(AppSettingsRepository.AppLanguage.FOLLOW_SYSTEM) }
        binding.rowZhCn.setOnClickListener { selectLanguage(AppSettingsRepository.AppLanguage.ZH_CN) }
        binding.rowEn.setOnClickListener { selectLanguage(AppSettingsRepository.AppLanguage.EN) }
        binding.radioFollowSystem.setOnClickListener { selectLanguage(AppSettingsRepository.AppLanguage.FOLLOW_SYSTEM) }
        binding.radioZhCn.setOnClickListener { selectLanguage(AppSettingsRepository.AppLanguage.ZH_CN) }
        binding.radioEn.setOnClickListener { selectLanguage(AppSettingsRepository.AppLanguage.EN) }

        refreshSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::appSettings.isInitialized) {
            refreshSelection()
        }
    }

    private fun refreshSelection() {
        val lang = appSettings.getLanguage()
        binding.radioFollowSystem.isChecked = lang == AppSettingsRepository.AppLanguage.FOLLOW_SYSTEM
        binding.radioZhCn.isChecked = lang == AppSettingsRepository.AppLanguage.ZH_CN
        binding.radioEn.isChecked = lang == AppSettingsRepository.AppLanguage.EN
    }

    private fun selectLanguage(lang: AppSettingsRepository.AppLanguage) {
        val current = appSettings.getLanguage()
        if (current == lang) return
        appSettings.setLanguage(lang)
        val locales = when (lang) {
            AppSettingsRepository.AppLanguage.FOLLOW_SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppSettingsRepository.AppLanguage.ZH_CN -> LocaleListCompat.forLanguageTags("zh-CN")
            AppSettingsRepository.AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
        }
        // AppCompat 会自动 recreate 任务栈内的 Activity（包括本页），无需手动 recreate。
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LanguageSettingsActivity::class.java))
        }
    }
}
