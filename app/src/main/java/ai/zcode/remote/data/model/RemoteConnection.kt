package ai.zcode.remote.data.model

import java.io.Serializable
import java.util.UUID

data class RemoteConnection(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var mid: String = "",
    var sid: String = "",
    var lastConnectedTime: Long = System.currentTimeMillis()
) : Serializable {
    /** 当前进程内的展示状态，不写入 SharedPreferences。 */
    @Transient
    var isConnected: Boolean = false
}
