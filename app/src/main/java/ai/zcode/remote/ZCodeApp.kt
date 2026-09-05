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

        // 进程重建（系统回收后被再次拉起）时恢复后台保活服务；
        // 服务自身 START_STICKY 也会被系统重启，此处兜底保证开关状态与服务一致。
        // 保活服务负责：防进程冻结（保证 WebView 页面 WS 事件持续镜像到通知）
        if (AppSettingsRepository.getInstance(this).isKeepAliveEnabled()) {
            ai.zcode.remote.service.KeepAliveService.start(this)
        }
    }

    companion object {
        lateinit var instance: ZCodeApp
            private set
    }
}
