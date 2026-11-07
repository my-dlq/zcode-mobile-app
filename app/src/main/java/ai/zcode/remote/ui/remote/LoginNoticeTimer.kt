package ai.zcode.remote.ui.remote

/** 只计算调用方传入的单调时间；页面检测、首次展示及状态持久化由 Activity 决定。 */
class LoginNoticeTimer(deadlineElapsedMs: Long = 0L) {
    var deadlineElapsedMs: Long = deadlineElapsedMs
        private set

    fun start(now: Long) {
        // 保留原截止时间，避免页面重复回调或 Activity 恢复时重新获得十秒。
        if (deadlineElapsedMs == 0L) deadlineElapsedMs = now + DURATION_MS
    }

    fun remainingMillis(now: Long): Long {
        if (deadlineElapsedMs == 0L || deadlineElapsedMs == DISMISSED) return 0L
        return (deadlineElapsedMs - now).coerceAtLeast(0L)
    }

    fun dismiss() {
        // 非零哨兵可随截止时间一起保存，手动关闭后即使恢复实例也不能再次启动。
        deadlineElapsedMs = DISMISSED
    }

    companion object {
        const val DURATION_MS = 10_000L
        private const val DISMISSED = 1L
    }
}
