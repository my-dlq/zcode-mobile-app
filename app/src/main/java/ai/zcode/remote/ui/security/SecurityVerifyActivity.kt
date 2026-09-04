
package ai.zcode.remote.ui.security

import androidx.fragment.app.FragmentActivity
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivitySecurityVerifyBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class SecurityVerifyActivity : FragmentActivity() {

    private lateinit var binding: ActivitySecurityVerifyBinding
    private lateinit var settings: AppSettingsRepository
    private var setupMode = false
    private var verifyOnly = false
    private var firstPattern: List<Int>? = null
    private var biometricPrompt: BiometricPrompt? = null
    private var biometricFailureCount = 0
    private var switchedToPattern = false

    private val lockHandler = Handler(Looper.getMainLooper())
    private val lockCountdownRunnable = object : Runnable {
        override fun run() {
            val remain = settings.getPatternLockUntil() - System.currentTimeMillis()
            if (remain > 0) {
                binding.tvHint.text = getString(
                    R.string.settings_security_pattern_lock_wait,
                    ((remain + 999) / 1000).toString()
                )
                lockHandler.postDelayed(this, 1000)
            } else {
                // 网格可见时提示绘制图案；否则（指纹优先、网格隐藏）用通用提示
                binding.tvHint.setText(
                    if (settings.isPatternEnabled() && binding.patternLock.visibility == View.VISIBLE) {
                        R.string.settings_security_pattern_lock_retry
                    } else {
                        R.string.settings_security_lock_retry
                    }
                )
                binding.patternLock.alpha = 1f
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)
        setupMode = intent.getBooleanExtra(EXTRA_SETUP_MODE, false)
        verifyOnly = intent.getBooleanExtra(EXTRA_VERIFY_ONLY, false)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.setText(if (setupMode) R.string.settings_security_set_pattern else R.string.settings_security_verify_title)
        binding.tvHint.setText(
            if (setupMode) R.string.settings_security_draw_pattern
            else R.string.settings_security_verify_hint
        )
        binding.patternLock.onPatternComplete = { pattern -> handlePattern(pattern) }

        val biometricAvailable = canUseBiometric()
        val fingerprintEnabled = !setupMode && biometricAvailable && settings.isFingerprintEnabled()
        val patternEnabled = setupMode || settings.isPatternEnabled()
        // 指纹优先：指纹+图案同时开启时只先弹指纹验证，图案网格不显示；
        // 仅指纹连续失败达阈值或系统锁定后，图案才作为兜底出现
        binding.patternLock.visibility = if (patternEnabled && !fingerprintEnabled) View.VISIBLE else View.GONE
        binding.btnFingerprint.visibility = if (fingerprintEnabled) View.VISIBLE else View.GONE
        binding.btnFingerprint.setOnClickListener { showBiometricPrompt() }
        if (fingerprintEnabled) {
            binding.patternLock.post { showBiometricPrompt() }
        }
        if (!setupMode && isVerifyLocked()) {
            // 锁定状态持久化：进程被杀死后重新进入，未过期的锁定仍然生效
            startLockCountdown()
        }
    }

    private fun handlePattern(pattern: List<Int>) {
        if (pattern.size < 4) {
            binding.tvHint.setText(R.string.settings_security_pattern_too_short)
            binding.patternLock.reset()
            return
        }
        if (setupMode) {
            if (firstPattern == null) {
                firstPattern = pattern
                binding.tvHint.setText(R.string.settings_security_draw_pattern_again)
                binding.patternLock.reset()
            } else if (firstPattern == pattern) {
                settings.setPattern(pattern)
                settings.setPatternEnabled(true)
                SecuritySession.unlock()
                Toast.makeText(this, R.string.settings_security_pattern_saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                firstPattern = null
                binding.tvHint.setText(R.string.settings_security_pattern_mismatch)
                binding.patternLock.reset()
            }
        } else if (isVerifyLocked()) {
            // 共享锁定期内（指纹/图案一起计）忽略所有图案输入，持续展示剩余等待秒数
            binding.patternLock.reset()
            startLockCountdown()
        } else if (settings.verifyPattern(pattern)) {
            settings.clearPatternLockState()
            if (verifyOnly) {
                setResult(RESULT_OK)
                finish()
            } else {
                SecuritySession.unlock()
                openMain()
            }
        } else {
            val failCount = settings.getPatternFailCount() + 1
            if (failCount >= PATTERN_FAIL_LIMIT) {
                // 每错满 5 次触发共享锁定：档位 1=60s、2=90s、3 及以后均为 180s
                settings.setPatternFailCount(0)
                triggerSharedLockout()
            } else {
                settings.setPatternFailCount(failCount)
                binding.tvHint.setText(R.string.settings_security_verify_failed)
                binding.patternLock.reset()
            }
        }
    }

    private fun showBiometricPrompt() {
        if (!canUseBiometric() || setupMode || !settings.isFingerprintEnabled()) return
        if (isVerifyLocked()) {
            // 共享锁定期内指纹与图案一起等待，不弹系统指纹窗
            startLockCountdown()
            return
        }
        biometricFailureCount = 0
        switchedToPattern = false
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                biometricPrompt = null
                settings.clearPatternLockState()
                if (verifyOnly) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    SecuritySession.unlock()
                    openMain()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                biometricPrompt = null
                when {
                    errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                        errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        // 指纹不累计错误次数（5 次阈值只由图案触发），
                        // 但系统锁定指纹时同样触发共享锁定：指纹与图案按同一档位一起等待
                        triggerSharedLockout()
                        fallbackToPattern()
                    }
                    (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) && !switchedToPattern -> {
                        // 取消指纹后把图案作为可选项展示（指纹按钮保留，两种方式任选）；
                        // 指纹+图案同时启用时，启动只先弹指纹，图案仅在取消指纹后出现
                        binding.tvHint.setText(
                            if (settings.isPatternEnabled()) R.string.settings_security_biometric_cancel
                            else R.string.settings_security_biometric_cancelled
                        )
                        if (settings.isPatternEnabled()) {
                            binding.patternLock.visibility = View.VISIBLE
                        }
                    }
                    !switchedToPattern ->
                        binding.tvHint.setText(R.string.settings_security_biometric_cancelled)
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                biometricFailureCount++
                if (biometricFailureCount >= BIOMETRIC_FAILURE_LIMIT && settings.isPatternEnabled()) {
                    fallbackToPattern()
                } else {
                    binding.tvHint.setText(
                        if (settings.isPatternEnabled()) R.string.settings_security_biometric_failed
                        else R.string.settings_security_biometric_failed_short
                    )
                }
            }
        })
        biometricPrompt = prompt
        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.settings_security_verify_title))
            .setSubtitle(getString(R.string.settings_security_use_fingerprint))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        // 仅生物识别（不含系统凭据）时必须设置非空负按钮文本，否则 build() 抛 IllegalArgumentException；
        // 负按钮统一为"取消"：即使图案已启用也不提供"图案验证"捷径，图案只在指纹失败达阈值后出现
        infoBuilder.setNegativeButtonText(getString(R.string.action_cancel))
        val info = infoBuilder.build()
        prompt.authenticate(info)
    }

    private fun fallbackToPattern() {
        if (!settings.isPatternEnabled() || switchedToPattern) return
        switchedToPattern = true
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        binding.btnFingerprint.visibility = View.GONE
        binding.patternLock.visibility = View.VISIBLE
        binding.tvHint.setText(R.string.settings_security_biometric_fallback)
        binding.patternLock.reset()
    }

    private fun canUseBiometric(): Boolean =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun isVerifyLocked(): Boolean =
        !setupMode && settings.getPatternLockUntil() > System.currentTimeMillis()

    private fun lockDuration(stage: Int): Long = when (stage) {
        1 -> LOCK_DURATION_STAGE1
        2 -> LOCK_DURATION_STAGE2
        else -> LOCK_DURATION_STAGE3
    }

    /** 触发共享锁定：档位递增后按 60s/90s/3min 计算截止时间并开始倒计时。 */
    private fun triggerSharedLockout() {
        val stage = settings.getPatternLockStage() + 1
        settings.setPatternLockStage(stage)
        settings.setPatternLockUntil(System.currentTimeMillis() + lockDuration(stage))
        binding.patternLock.reset()
        startLockCountdown()
    }

    private fun startLockCountdown() {
        binding.patternLock.alpha = 0.4f
        lockHandler.removeCallbacks(lockCountdownRunnable)
        lockCountdownRunnable.run()
    }

    override fun onDestroy() {
        lockHandler.removeCallbacks(lockCountdownRunnable)
        super.onDestroy()
    }

    private fun openMain() {
        startActivity(Intent(this, ai.zcode.remote.ui.main.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    companion object {
        private const val EXTRA_SETUP_MODE = "extra_setup_mode"
        private const val EXTRA_VERIFY_ONLY = "extra_verify_only"
        private const val BIOMETRIC_FAILURE_LIMIT = 3

        // 图案锁定：每错满 5 次触发一次等待，档位 1=60s、2=90s、3 及以后均为 180s
        private const val PATTERN_FAIL_LIMIT = 5
        private const val LOCK_DURATION_STAGE1 = 60_000L
        private const val LOCK_DURATION_STAGE2 = 90_000L
        private const val LOCK_DURATION_STAGE3 = 180_000L

        fun createIntent(context: Context, setupMode: Boolean, verifyOnly: Boolean = false): Intent =
            Intent(context, SecurityVerifyActivity::class.java).apply {
                putExtra(EXTRA_SETUP_MODE, setupMode)
                putExtra(EXTRA_VERIFY_ONLY, verifyOnly)
            }

        fun start(context: Context, setupMode: Boolean, verifyOnly: Boolean = false) {
            context.startActivity(createIntent(context, setupMode, verifyOnly))
        }
    }
}
