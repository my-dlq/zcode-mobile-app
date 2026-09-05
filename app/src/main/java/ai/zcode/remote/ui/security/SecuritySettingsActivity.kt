
package ai.zcode.remote.ui.security

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivitySecuritySettingsBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecuritySettingsBinding
    private lateinit var settings: AppSettingsRepository

    /** 关闭验证开关前需先通过验证（指纹/图案任一成功）；验证成功后执行的动作。 */
    private var disableAfterVerify: (() -> Unit)? = null
    private val verifyToDisableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = disableAfterVerify
        disableAfterVerify = null
        if (result.resultCode == RESULT_OK) action?.invoke()
        // 无论验证结果如何都按当前设置刷新开关，取消/失败时开关会回弹
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecuritySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        // 点图案栏整行 = 拨动开关：开启/关闭（不再单独承担"修改图案"入口）
        binding.rowPattern.setOnClickListener {
            binding.switchPattern.toggle()
        }
        binding.rowFingerprint.setOnClickListener {
            if (isFingerprintAvailable()) {
                binding.switchFingerprint.toggle()
            } else {
                Toast.makeText(this, R.string.settings_security_fingerprint_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        binding.switchPattern.setOnCheckedChangeListener { _, checked ->
            handlePatternToggle(checked)
        }
        binding.switchFingerprint.setOnCheckedChangeListener { _, checked ->
            handleFingerprintToggle(checked)
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshState()
    }

    private fun handlePatternToggle(checked: Boolean) {
        if (checked) {
            // 每次开启都进入设置流程画新图案（覆盖旧图案）：先把开关回弹，
            // 待设置页画完两遍一致后由那边 setPatternEnabled(true) 并刷新
            binding.switchPattern.setOnCheckedChangeListener(null)
            binding.switchPattern.isChecked = false
            binding.switchPattern.setOnCheckedChangeListener { _, value -> handlePatternToggle(value) }
            SecurityVerifyActivity.start(this, setupMode = true)
        } else if (settings.isSecurityVerificationEnabled()) {
            // 关闭图案同样需要先通过一次验证（指纹或图案任一成功），防止误触关闭
            verifyToDisable {
                settings.setPatternEnabled(false)
            }
        } else {
            settings.setPatternEnabled(false)
            refreshState()
        }
    }

    private fun handleFingerprintToggle(checked: Boolean) {
        if (checked && !isFingerprintAvailable()) {
            binding.switchFingerprint.setOnCheckedChangeListener(null)
            binding.switchFingerprint.isChecked = false
            binding.switchFingerprint.setOnCheckedChangeListener { _, value -> handleFingerprintToggle(value) }
            Toast.makeText(this, R.string.settings_security_fingerprint_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (checked) {
            // 开启指纹前先弹系统指纹验证：验证通过才落设置，避免误触开关直接开启安全验证
            showFingerprintEnablePrompt()
        } else if (settings.isSecurityVerificationEnabled()) {
            // 关闭指纹同样需要先通过一次验证（指纹或图案任一成功），防止误触关闭
            verifyToDisable {
                settings.setFingerprintEnabled(false)
            }
        } else {
            settings.setFingerprintEnabled(false)
        }
    }

    /** 拉起仅验证模式的验证页，验证成功（指纹或图案任一成功）后执行 disable 动作。 */
    private fun verifyToDisable(disable: () -> Unit) {
        if (disableAfterVerify != null) return
        disableAfterVerify = disable
        // 必须经 launcher.launch() 启动，setResult 才会回传（普通 startActivity 会丢失结果）
        verifyToDisableLauncher.launch(
            SecurityVerifyActivity.createIntent(this, setupMode = false, verifyOnly = true)
        )
    }

    private fun showFingerprintEnablePrompt() {
        if (fingerprintPromptShowing) return
        fingerprintPromptShowing = true
        // 验证期间开关先回弹，验证成功后再由 refreshState() 置为开
        binding.switchFingerprint.setOnCheckedChangeListener(null)
        binding.switchFingerprint.isChecked = false
        binding.switchFingerprint.setOnCheckedChangeListener { _, value -> handleFingerprintToggle(value) }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                fingerprintPromptShowing = false
                settings.setFingerprintEnabled(true)
                refreshState()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                fingerprintPromptShowing = false
                Toast.makeText(
                    this@SecuritySettingsActivity,
                    R.string.settings_security_fingerprint_verify_rejected,
                    Toast.LENGTH_SHORT
                ).show()
                refreshState()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // 弹窗保持，允许重试；连续失败后由系统弹窗转错误回调
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_security_fingerprint_title))
                .setSubtitle(getString(R.string.settings_security_fingerprint_verify_hint))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
        )
    }

    private fun refreshState() {
        binding.tvPatternSummary.setText(
            if (settings.isPatternConfigured()) R.string.settings_security_pattern_configured
            else R.string.settings_security_pattern_not_configured
        )
        binding.switchPattern.setOnCheckedChangeListener(null)
        binding.switchPattern.isChecked = settings.isPatternEnabled()
        binding.switchPattern.setOnCheckedChangeListener { _, checked -> handlePatternToggle(checked) }

        val available = isFingerprintAvailable()
        binding.rowFingerprint.alpha = if (available) 1f else 0.55f
        binding.switchFingerprint.isEnabled = available
        binding.switchFingerprint.setOnCheckedChangeListener(null)
        binding.switchFingerprint.isChecked = settings.isFingerprintEnabled()
        binding.switchFingerprint.setOnCheckedChangeListener { _, checked -> handleFingerprintToggle(checked) }
        binding.tvFingerprintSummary.setText(
            if (available) R.string.settings_security_fingerprint_summary
            else R.string.settings_security_fingerprint_unavailable
        )
    }

    private var fingerprintPromptShowing = false

    private fun isFingerprintAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SecuritySettingsActivity::class.java))
        }
    }
}
