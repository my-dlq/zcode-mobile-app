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
