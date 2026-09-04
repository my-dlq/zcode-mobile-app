package ai.zcode.remote.data.repository

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/** 应用级显示设置，与连接数据和更新设置共用现有偏好文件。 */
class AppSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFullscreenEnabled(): Boolean = prefs.getBoolean(KEY_FULLSCREEN, true)

    fun setFullscreenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
    }

    fun getThemeMode(): ThemeMode {
        return when (prefs.getString(KEY_THEME_MODE, ThemeMode.LIGHT.value)) {
            ThemeMode.DARK.value -> ThemeMode.DARK
            else -> ThemeMode.LIGHT
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.value).apply()
    }

    fun isPatternConfigured(): Boolean = prefs.contains(KEY_PATTERN_HASH)

    fun isPatternEnabled(): Boolean =
        isPatternConfigured() && prefs.getBoolean(KEY_PATTERN_ENABLED, false)

    fun setPatternEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PATTERN_ENABLED, enabled).apply()
    }

    fun setPattern(pattern: List<Int>) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPattern(pattern, salt)
        prefs.edit()
            .putString(KEY_PATTERN_SALT, salt.toHex())
            .putString(KEY_PATTERN_HASH, hash)
            .apply()
    }

    fun verifyPattern(pattern: List<Int>): Boolean {
        val salt = prefs.getString(KEY_PATTERN_SALT, null)?.fromHex() ?: return false
        val expected = prefs.getString(KEY_PATTERN_HASH, null) ?: return false
        val actual = hashPattern(pattern, salt)
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }

    fun isFingerprintEnabled(): Boolean = prefs.getBoolean(KEY_FINGERPRINT_ENABLED, false)

    fun setFingerprintEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FINGERPRINT_ENABLED, enabled).apply()
    }

    /** 图案解锁：当前窗口内已累计的错误次数（达到阈值触发锁定后清零）。 */
    fun getPatternFailCount(): Int = prefs.getInt(KEY_PATTERN_FAIL_COUNT, 0)

    fun setPatternFailCount(count: Int) {
        prefs.edit().putInt(KEY_PATTERN_FAIL_COUNT, count).apply()
    }

    /** 图案解锁：已触发的锁定档位（1=60s，2=90s，之后每档 3 分钟）。 */
    fun getPatternLockStage(): Int = prefs.getInt(KEY_PATTERN_LOCK_STAGE, 0)

    fun setPatternLockStage(stage: Int) {
        prefs.edit().putInt(KEY_PATTERN_LOCK_STAGE, stage).apply()
    }

    /** 图案解锁：当前锁定截止时间（毫秒时间戳），0 表示未锁定。 */
    fun getPatternLockUntil(): Long = prefs.getLong(KEY_PATTERN_LOCK_UNTIL, 0L)

    fun setPatternLockUntil(until: Long) {
        prefs.edit().putLong(KEY_PATTERN_LOCK_UNTIL, until).apply()
    }

    /** 图案校验正确后清零全部累计：窗口错误次数、锁定档位与截止时间。 */
    fun clearPatternLockState() {
        prefs.edit()
            .putInt(KEY_PATTERN_FAIL_COUNT, 0)
            .putInt(KEY_PATTERN_LOCK_STAGE, 0)
            .putLong(KEY_PATTERN_LOCK_UNTIL, 0L)
            .apply()
    }

    fun isSecurityVerificationEnabled(): Boolean =
        isPatternEnabled() || isFingerprintEnabled()

    private fun hashPattern(pattern: List<Int>, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pattern.joinToString(",").toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    enum class ThemeMode(val value: String) {
        LIGHT("light"),
        DARK("dark")
    }

    companion object {
        private const val PREFS_NAME = "zcode_remote_prefs"
        private const val KEY_FULLSCREEN = "key_fullscreen_enabled"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_PATTERN_SALT = "key_pattern_salt"
        private const val KEY_PATTERN_HASH = "key_pattern_hash"
        private const val KEY_PATTERN_ENABLED = "key_pattern_enabled"
        private const val KEY_FINGERPRINT_ENABLED = "key_fingerprint_enabled"
        private const val KEY_PATTERN_FAIL_COUNT = "key_pattern_fail_count"
        private const val KEY_PATTERN_LOCK_STAGE = "key_pattern_lock_stage"
        private const val KEY_PATTERN_LOCK_UNTIL = "key_pattern_lock_until"

        @Volatile
        private var instance: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: AppSettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
