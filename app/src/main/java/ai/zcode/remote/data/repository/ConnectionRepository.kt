package ai.zcode.remote.data.repository

import ai.zcode.remote.data.model.RemoteConnection
import android.net.Uri
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConnectionRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    @Volatile
    private var activeConnectionId: String? = null

    fun getAllConnections(): MutableList<RemoteConnection> {
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<RemoteConnection>>() {}.type
            val list: MutableList<RemoteConnection> = gson.fromJson(json, type) ?: mutableListOf()
            list.forEach { it.isConnected = it.id == activeConnectionId }
            list.sortedByDescending { it.lastConnectedTime }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveConnection(connection: RemoteConnection) {
        val list = getAllConnections()
        val index = list.indexOfFirst { existing ->
            existing.id == connection.id || sameConnection(existing, connection)
        }

        if (index >= 0) {
            val existing = list[index]
            connection.copyInto(existing)
        } else {
            list.add(0, connection)
        }

        saveList(list)
    }

    private fun sameConnection(first: RemoteConnection, second: RemoteConnection): Boolean {
        val firstUrl = normalizeUrl(first.url)
        val secondUrl = normalizeUrl(second.url)
        if (firstUrl.isNotEmpty() && secondUrl.isNotEmpty() && firstUrl == secondUrl) return true
        return first.mid.isNotBlank() && first.sid.isNotBlank() &&
            first.mid == second.mid && first.sid == second.sid
    }

    private fun normalizeUrl(rawUrl: String): String {
        return try {
            val uri = Uri.parse(rawUrl.trim())
            val query = uri.queryParameterNames
                .sorted()
                .joinToString("&") { key ->
                    uri.getQueryParameters(key).sorted().joinToString("&") { value ->
                        "${Uri.encode(key)}=${Uri.encode(value)}"
                    }
                }
            Uri.Builder()
                .scheme(uri.scheme?.lowercase())
                .encodedAuthority(uri.encodedAuthority?.lowercase())
                .path(uri.path)
                .encodedQuery(query.ifEmpty { null })
                .build()
                .toString()
                .trimEnd('/')
        } catch (_: Exception) {
            rawUrl.trim().removeSuffix("/")
        }
    }

    private fun RemoteConnection.copyInto(target: RemoteConnection) {
        target.name = name
        target.url = url
        target.mid = mid
        target.sid = sid
        target.lastConnectedTime = lastConnectedTime
    }

    fun deleteConnection(id: String) {
        val list = getAllConnections()
        list.removeAll { it.id == id }
        saveList(list)
    }

    fun updateLastConnected(id: String) {
        val list = getAllConnections()
        val conn = list.find { it.id == id }
        if (conn != null) {
            activeConnectionId = id
            conn.lastConnectedTime = System.currentTimeMillis()
            saveList(list)
            // 持久化活跃连接 URL：进程被系统回收后，从 launcher 切回时
            // MainActivity 可据此自动恢复远程页
            prefs.edit().putString(KEY_LAST_ACTIVE_URL, conn.url)
                .putString(KEY_LAST_ACTIVE_NAME, conn.name).apply()
        }
    }

    /** 获取最近一次活跃连接的 URL（用于进程被杀后自动恢复远程页）。 */
    fun getLastActiveUrl(): String? = prefs.getString(KEY_LAST_ACTIVE_URL, null)

    /** 获取最近一次活跃连接的名称。 */
    fun getLastActiveName(): String? = prefs.getString(KEY_LAST_ACTIVE_NAME, null)

    /** 清除活跃连接标记（用户主动退出远程页时调用）。 */
    fun clearLastActive() {
        activeConnectionId = null
        prefs.edit().remove(KEY_LAST_ACTIVE_URL).remove(KEY_LAST_ACTIVE_NAME).apply()
    }

    private fun saveList(list: List<RemoteConnection>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_CONNECTIONS, json).apply()
    }

    companion object {
        private const val PREFS_NAME = "zcode_remote_prefs"
        private const val KEY_CONNECTIONS = "key_connections"
        private const val KEY_LAST_ACTIVE_URL = "key_last_active_url"
        private const val KEY_LAST_ACTIVE_NAME = "key_last_active_name"

        @Volatile
        private var instance: ConnectionRepository? = null

        fun getInstance(context: Context): ConnectionRepository {
            return instance ?: synchronized(this) {
                instance ?: ConnectionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
