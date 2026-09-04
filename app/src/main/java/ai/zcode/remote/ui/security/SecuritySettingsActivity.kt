
package ai.zcode.remote.ui.security

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivitySecuritySettingsBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricManager

class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecuritySettingsBinding
    private lateinit var settings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecuritySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowPattern.setOnClickListener {
            SecurityVerifyActivity.start(this, setupMode = true)
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
            if (checked && !isFingerprintAvailable()) {
                binding.switchFingerprint.setOnCheckedChangeListener(null)
                binding.switchFingerprint.isChecked = false
                binding.switchFingerprint.setOnCheckedChangeListener { _, value -> handleFingerprintToggle(value) }
                Toast.makeText(this, R.string.settings_security_fingerprint_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                settings.setFingerprintEnabled(checked)
            }
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshState()
    }

    private fun handlePatternToggle(checked: Boolean) {
        if (checked && !settings.isPatternConfigured()) {
            binding.switchPattern.setOnCheckedChangeListener(null)
            binding.switchPattern.isChecked = false
            binding.switchPattern.setOnCheckedChangeListener { _, value -> handlePatternToggle(value) }
            SecurityVerifyActivity.start(this, setupMode = true)
        } else {
            settings.setPatternEnabled(checked)
            refreshState()
        }
    }

    private fun handleFingerprintToggle(checked: Boolean) {
        if (checked && !isFingerprintAvailable()) {
            binding.switchFingerprint.setOnCheckedChangeListener(null)
            binding.switchFingerprint.isChecked = false
            binding.switchFingerprint.setOnCheckedChangeListener { _, value -> handleFingerprintToggle(value) }
        } else {
            settings.setFingerprintEnabled(checked)
        }
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

    private fun isFingerprintAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SecuritySettingsActivity::class.java))
        }
    }
}
