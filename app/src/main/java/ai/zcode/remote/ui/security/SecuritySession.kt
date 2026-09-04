package ai.zcode.remote.ui.security

/** 当前进程内的安全验证状态；进程结束或用户退出首页后自动失效。 */
object SecuritySession {
    @Volatile
    var isUnlocked: Boolean = false
        private set

    fun unlock() {
        isUnlocked = true
    }

    fun lock() {
        isUnlocked = false
    }
}
