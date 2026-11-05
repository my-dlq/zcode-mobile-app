package ai.zcode.remote.data.model

import java.security.MessageDigest

/** 登录空间与连接名称、二维码临时参数无关；重置时换代，旧回调和通知立即失效。 */
data class ConnectionLoginSpace(val connectionId: String, val generation: Long = 0) {
    init {
        require(connectionId.isNotBlank())
        require(generation >= 0)
    }

    val profileName: String
        get() = PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(connectionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) } + "_g$generation"

    // 首代保留原通知身份，兼容升级前尚未点击的通知。
    val sourceId: String
        get() = if (generation == 0L) connectionId else "$connectionId$SEPARATOR$generation"

    companion object {
        const val PREFIX = "zcode_conn_"
        private const val SEPARATOR = ":login:"

        fun fromSourceId(sourceId: String): ConnectionLoginSpace? {
            if (sourceId.isBlank() || sourceId.startsWith("http://") || sourceId.startsWith("https://")) return null
            val index = sourceId.lastIndexOf(SEPARATOR)
            if (index < 0) return ConnectionLoginSpace(sourceId)
            val generation = sourceId.substring(index + SEPARATOR.length).toLongOrNull() ?: return null
            val id = sourceId.substring(0, index)
            return if (id.isBlank() || generation < 0) null else ConnectionLoginSpace(id, generation)
        }
    }
}
