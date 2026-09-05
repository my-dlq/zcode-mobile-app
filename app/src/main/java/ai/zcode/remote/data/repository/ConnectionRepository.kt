package ai.zcode.remote.data.repository

import ai.zcode.remote.data.model.RemoteConnection
import android.net.Uri
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 连接仓库：SharedPreferences + Gson 持久化。
 *
 * 多连接并行模型：每个连接独立记录 lastConnectedTime / lastTaskId，
 * 列表页按最近使用时间排序，"已连接"状态由 isConnected 标记（内存态）。
 * 进程被杀后通过 lastActiveUrl/lastActiveName/lastActiveTaskId 恢复
 * 最近一次活跃的连接及其任务会话。
 */
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
            // 不再按最近连接时间自动排序：顺序由用户手动拖动决定，APP 不自动调整
            list
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
            // 新连接追加到末尾（不再插入到开头），保持用户手动排序的顺序
            list.add(connection)
        }

        saveList(list)
    }

    /** 保存用户手动排序后的连接顺序。 */
    fun saveOrder(orderedIds: List<String>) {
        val list = getAllConnections()
        val idToConn = list.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { idToConn[it] }.toMutableList()
        // 防御：如果有新增连接不在 orderedIds 中，追加到末尾
        for (conn in list) {
            if (conn.id !in orderedIds) reordered.add(conn)
        }
        saveList(reordered)
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
        // lastTaskId 不在 copyInto 中覆盖——它由 updateLastTaskId 独立更新
    }

    fun deleteConnection(id: String) {
        val list = getAllConnections()
        list.removeAll { it.id == id }
        saveList(list)
    }

    fun updateLastConnected(id: String) {
        val list = getAllConnections()
        val conn = list.find { it.id == id } ?: return
        activeConnectionId = id
        conn.lastConnectedTime = System.currentTimeMillis()
        saveList(list)
        // 持久化活跃连接：进程被系统回收后恢复用
        prefs.edit()
            .putString(KEY_LAST_ACTIVE_URL, conn.url)
            .putString(KEY_LAST_ACTIVE_NAME, conn.name)
            .putString(KEY_LAST_ACTIVE_TASK_ID, conn.lastTaskId)
            .apply()
    }

    /**
     * 记录某连接当前所在的任务会话：切出远程页时调用，
     * 下次切回该连接时自动跳转到此会话。
     */
    fun updateLastTaskId(connectionId: String, taskId: String) {
        val list = getAllConnections()
        val conn = list.find { it.id == connectionId } ?: return
        conn.lastTaskId = taskId
        saveList(list)
        // 如果这是当前活跃连接，同步更新恢复信息
        if (connectionId == activeConnectionId) {
            prefs.edit().putString(KEY_LAST_ACTIVE_TASK_ID, taskId).apply()
        }
    }

    /** 通过 URL 查找连接（用于 RemoteControlActivity 反查 connectionId）。 */
    fun findByUrl(url: String): RemoteConnection? {
        val normalized = normalizeUrl(url)
        return getAllConnections().firstOrNull { normalizeUrl(it.url) == normalized }
    }

    /** 获取最近一次活跃连接的 URL（用于进程被杀后自动恢复远程页）。 */
    fun getLastActiveUrl(): String? = prefs.getString(KEY_LAST_ACTIVE_URL, null)

    /** 获取最近一次活跃连接的名称。 */
    fun getLastActiveName(): String? = prefs.getString(KEY_LAST_ACTIVE_NAME, null)

    /** 获取最近一次活跃连接的任务会话 ID。 */
    fun getLastActiveTaskId(): String = prefs.getString(KEY_LAST_ACTIVE_TASK_ID, "") ?: ""

    private fun saveList(list: List<RemoteConnection>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_CONNECTIONS, json).apply()
    }

    companion object {
        private const val PREFS_NAME = "zcode_remote_prefs"
        private const val KEY_CONNECTIONS = "key_connections"
        private const val KEY_LAST_ACTIVE_URL = "key_last_active_url"
        private const val KEY_LAST_ACTIVE_NAME = "key_last_active_name"
        private const val KEY_LAST_ACTIVE_TASK_ID = "key_last_active_task_id"

        @Volatile
        private var instance: ConnectionRepository? = null

        fun getInstance(context: Context): ConnectionRepository {
            return instance ?: synchronized(this) {
                instance ?: ConnectionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
