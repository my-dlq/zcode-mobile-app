package ai.zcode.remote.data.model

import java.io.Serializable
import java.util.UUID

data class RemoteConnection(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var mid: String = "",
    var sid: String = "",
    var lastConnectedTime: Long = System.currentTimeMillis(),
    /** 最后一次活跃的任务会话 ID：切出远程页时记录，切回时自动跳转到该会话。 */
    var lastTaskId: String = "",
    /** 旧数据缺省为 0；仅重置登录或更换目标电脑时递增。 */
    var loginGeneration: Long = 0,
) : Serializable {
    val loginSpace: ConnectionLoginSpace
        get() = ConnectionLoginSpace(id, loginGeneration)

    /** 当前进程内的展示状态，不写入 SharedPreferences。 */
    @Transient
    var isConnected: Boolean = false
}
