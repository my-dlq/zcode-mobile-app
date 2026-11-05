package ai.zcode.remote.ui.remote.web

import android.content.Context
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import ai.zcode.remote.data.model.ConnectionLoginSpace
import ai.zcode.remote.data.repository.ConnectionRepository

/** 前台与隐藏监听共用此入口；必须在设置、Bridge 和首次导航之前绑定。 */
object ConnectionWebProfile {
    enum class Mode { ISOLATED, SHARED }

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    @MainThread
    fun bind(webView: WebView, space: ConnectionLoginSpace): Mode {
        if (!isSupported()) return Mode.SHARED
        // 设置失败时由调用方停止加载，绝不能静默使用默认账号。
        WebViewCompat.setProfile(webView, space.profileName)
        return Mode.ISOLATED
    }

    /** 仅冷启动时执行；当前进程已经加载的 Profile 不能直接删除。 */
    @MainThread
    fun cleanPendingAtStartup(context: Context) {
        val repository = ConnectionRepository.getInstance(context)
        val pending = repository.pendingLoginProfileDeletions()
        if (pending.isEmpty() || !isSupported()) return
        val store = ProfileStore.getInstance()
        val completed = pending.filterTo(mutableSetOf()) { name ->
            name.startsWith(ConnectionLoginSpace.PREFIX) && runCatching {
                store.deleteProfile(name)
            }.isSuccess
        }
        repository.completeLoginProfileDeletions(completed)
        val introductions = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        completed.forEach { introductions.remove(it) }
        introductions.apply()
    }

    fun hasShownIntroduction(context: Context, space: ConnectionLoginSpace): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(space.profileName, false)

    fun markIntroductionShown(context: Context, space: ConnectionLoginSpace) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(space.profileName, true).apply()
    }

    private const val PREFS = "zcode_connection_profiles"
}
