package ai.zcode.remote.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 更新功能持久化仓库：启动静默检查开关 + 被忽略的版本号。
 * 复用 ConnectionRepository 的 prefs 文件与单例写法（双检锁）。
 */
class UpdateRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 启动时是否自动检查更新（悬浮控制面板中的开关控制，默认开启） */
    fun isAutoCheckEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    /** 用户选择「忽略此版本」的版本号（不含 v 前缀），仅静默检查时生效；空串表示未忽略 */
    fun getIgnoredVersion(): String = prefs.getString(KEY_IGNORED_VERSION, "") ?: ""

    fun setIgnoredVersion(version: String) {
        prefs.edit().putString(KEY_IGNORED_VERSION, version).apply()
    }

    companion object {
        private const val PREFS_NAME = "zcode_remote_prefs"
        private const val KEY_AUTO_CHECK = "key_auto_check_update"
        private const val KEY_IGNORED_VERSION = "key_ignored_version"

        @Volatile
        private var instance: UpdateRepository? = null

        fun getInstance(context: Context): UpdateRepository {
            return instance ?: synchronized(this) {
                instance ?: UpdateRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
