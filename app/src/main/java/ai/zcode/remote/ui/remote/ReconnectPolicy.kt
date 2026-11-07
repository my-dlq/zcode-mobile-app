package ai.zcode.remote.ui.remote

/** 网络恢复/长连接断开后的有限退避策略。 */
internal object ReconnectPolicy {
    private const val INITIAL_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 60_000L

    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 6)
        return (INITIAL_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}

/** renderer 死亡后只允许一次重建/交接；恢复与网络是否在线无关，不能再使用旧 view。 */
internal class RendererRecoveryState {
    enum class Action { NONE, RECREATE, HANDOVER }

    var pending = false
        private set
    var requested = false
        private set

    fun markGone(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    fun nextAction(ownsConnection: Boolean, visible: Boolean): Action {
        if (!pending || requested || !ownsConnection) return Action.NONE
        requested = true
        return if (visible) Action.RECREATE else Action.HANDOVER
    }
}
