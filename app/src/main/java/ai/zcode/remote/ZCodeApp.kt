package ai.zcode.remote

import android.app.Application
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.service.BackgroundEventMonitor

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

        // 后台事件监听与保活服务解耦：只要进程在就为所有有 WS URL 的连接
        // 维持后台 WebSocket，让"切到会话 A 时会话 B 的审批/完成也能收到通知"。
        // 监听器本身很轻量（一条 WS + 心跳），不依赖"后台保活"开关——
        // 保活服务仅负责防止整个进程被系统杀掉。
        eventMonitor = BackgroundEventMonitor(this).also {
            it.startAll()
            BackgroundEventMonitor.registerInstance(it)
        }

        // 进程重建（系统回收后被再次拉起）时恢复后台保活服务；
        // 服务自身 START_STICKY 也会被系统重启，此处兜底保证开关状态与服务一致
        if (AppSettingsRepository.getInstance(this).isKeepAliveEnabled()) {
            ai.zcode.remote.service.KeepAliveService.start(this)
        }
    }

    companion object {
        lateinit var instance: ZCodeApp
            private set

        /** 全局后台事件监听，由 Application 持有，进程存活期间一直在跑。 */
        @Volatile
        var eventMonitor: BackgroundEventMonitor? = null
            private set
    }
}
