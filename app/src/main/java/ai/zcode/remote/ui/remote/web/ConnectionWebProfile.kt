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
        cleanIntroductionProfiles(context, repository.getAllConnections().map { it.loginSpace }, completed)
    }

    internal fun cleanIntroductionProfiles(
        context: Context,
        currentSpaces: Iterable<ConnectionLoginSpace>,
        completed: Set<String>,
    ) {
        // 升级前已读且已重置的连接，唯一记录可能还在待清理的旧代；必须先迁移再删除。
        currentSpaces.forEach { hasShownIntroduction(context, it) }
        val introductions = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        completed.forEach { introductions.remove(it) }
        introductions.apply()
    }

    fun hasShownIntroduction(context: Context, space: ConnectionLoginSpace): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val connectionKey = INTRODUCTION_CONNECTION_PREFIX + space.connectionId
        if (preferences.getBoolean(connectionKey, false)) return true

        // 说明只对连接展示一次，不随登录空间换代；升级时兼容任意旧代已展示的记录。
        val legacyPrefix = space.profileName.substringBeforeLast("_g") + "_g"
        val shownBefore = preferences.getBoolean(space.profileName, false) || preferences.all.any { (key, value) ->
            key.startsWith(legacyPrefix) && value == true
        }
        if (shownBefore) preferences.edit().putBoolean(connectionKey, true).apply()
        return shownBefore
    }

    fun markIntroductionShown(context: Context, space: ConnectionLoginSpace) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(INTRODUCTION_CONNECTION_PREFIX + space.connectionId, true).apply()
    }

    private const val INTRODUCTION_CONNECTION_PREFIX = "introduction_connection:"
    private const val PREFS = "zcode_connection_profiles"
}
