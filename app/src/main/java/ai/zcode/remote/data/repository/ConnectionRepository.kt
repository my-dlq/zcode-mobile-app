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

    fun getAllConnections(): MutableList<RemoteConnection> {
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<RemoteConnection>>() {}.type
            val list: MutableList<RemoteConnection> = gson.fromJson(json, type) ?: mutableListOf()
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

        if (connection.isDefault) {
            // 如果设为了默认，将其他连接的默认状态清除
            list.forEach { it.isDefault = false }
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
        target.isDefault = isDefault
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
            conn.lastConnectedTime = System.currentTimeMillis()
            saveList(list)
        }
    }

    fun setDefaultConnection(id: String, isDefault: Boolean) {
        val list = getAllConnections()
        list.forEach {
            it.isDefault = if (it.id == id) isDefault else false
        }
        saveList(list)
    }

    fun getDefaultConnection(): RemoteConnection? {
        val list = getAllConnections()
        return list.firstOrNull { it.isDefault }
    }

    private fun saveList(list: List<RemoteConnection>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_CONNECTIONS, json).apply()
    }

    companion object {
        private const val PREFS_NAME = "zcode_remote_prefs"
        private const val KEY_CONNECTIONS = "key_connections"

        @Volatile
        private var instance: ConnectionRepository? = null

        fun getInstance(context: Context): ConnectionRepository {
            return instance ?: synchronized(this) {
                instance ?: ConnectionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
