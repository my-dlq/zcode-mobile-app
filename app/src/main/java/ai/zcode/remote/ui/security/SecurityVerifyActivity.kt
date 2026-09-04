
package ai.zcode.remote.ui.security

import androidx.fragment.app.FragmentActivity
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivitySecurityVerifyBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class SecurityVerifyActivity : FragmentActivity() {

    private lateinit var binding: ActivitySecurityVerifyBinding
    private lateinit var settings: AppSettingsRepository
    private var setupMode = false
    private var firstPattern: List<Int>? = null
    private var biometricPrompt: BiometricPrompt? = null
    private var biometricFailureCount = 0
    private var switchedToPattern = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)
        setupMode = intent.getBooleanExtra(EXTRA_SETUP_MODE, false)

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
        binding.patternLock.visibility = if (patternEnabled) View.VISIBLE else View.GONE
        binding.btnFingerprint.visibility = if (fingerprintEnabled) View.VISIBLE else View.GONE
        binding.btnFingerprint.setOnClickListener { showBiometricPrompt() }
        if (fingerprintEnabled) {
            binding.patternLock.post { showBiometricPrompt() }
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
        } else if (settings.verifyPattern(pattern)) {
            SecuritySession.unlock()
            openMain()
        } else {
            binding.tvHint.setText(R.string.settings_security_verify_failed)
            binding.patternLock.reset()
        }
    }

    private fun showBiometricPrompt() {
        if (!canUseBiometric() || setupMode || !settings.isFingerprintEnabled()) return
        biometricFailureCount = 0
        switchedToPattern = false
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                biometricPrompt = null
                SecuritySession.unlock()
                openMain()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                biometricPrompt = null
                if (errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                    errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    fallbackToPattern()
                } else if (!switchedToPattern) {
                    binding.tvHint.setText(R.string.settings_security_biometric_cancel)
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                biometricFailureCount++
                if (biometricFailureCount >= BIOMETRIC_FAILURE_LIMIT && settings.isPatternEnabled()) {
                    fallbackToPattern()
                } else {
                    binding.tvHint.setText(R.string.settings_security_biometric_failed)
                }
            }
        })
        biometricPrompt = prompt
        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.settings_security_verify_title))
            .setSubtitle(getString(R.string.settings_security_use_fingerprint))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (settings.isPatternEnabled()) {
            infoBuilder.setNegativeButtonText(getString(R.string.settings_security_pattern_title))
        }
        val info = infoBuilder.build()
        prompt.authenticate(info)
    }

    private fun fallbackToPattern() {
        if (!settings.isPatternEnabled() || switchedToPattern) return
        switchedToPattern = true
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        binding.patternLock.visibility = View.VISIBLE
        binding.tvHint.setText(R.string.settings_security_biometric_fallback)
        binding.patternLock.reset()
    }

    private fun canUseBiometric(): Boolean =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun openMain() {
        startActivity(Intent(this, ai.zcode.remote.ui.main.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    companion object {
        private const val EXTRA_SETUP_MODE = "extra_setup_mode"
        private const val BIOMETRIC_FAILURE_LIMIT = 3

        fun start(context: Context, setupMode: Boolean) {
            context.startActivity(Intent(context, SecurityVerifyActivity::class.java).apply {
                putExtra(EXTRA_SETUP_MODE, setupMode)
            })
        }
    }
}
