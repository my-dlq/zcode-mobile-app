package ai.zcode.remote

import android.app.Application
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import ai.zcode.remote.data.repository.AppSettingsRepository

class ZCodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 在第一个 Activity 创建前恢复用户选择的主题（亮色/暗色）。
        val themeMode = AppSettingsRepository.getInstance(this).getThemeMode()
        AppCompatDelegate.setDefaultNightMode(
            if (themeMode != AppSettingsRepository.ThemeMode.LIGHT) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        // 开启 WebView 调试支持（便于必要时通过 Chrome DevTools 排查）
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    companion object {
        lateinit var instance: ZCodeApp
            private set
    }
}
