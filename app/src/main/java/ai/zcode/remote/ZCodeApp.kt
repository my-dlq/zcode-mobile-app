package ai.zcode.remote

import android.app.Application
import android.webkit.WebView

class ZCodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

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
