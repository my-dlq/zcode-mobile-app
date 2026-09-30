package ai.zcode.remote.ui.remote.event

import android.view.Gravity
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.ui.BaseActivity
import ai.zcode.remote.utils.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Main-thread reminders for task conversations and explicit notification entry. */
class ClaimCampaignDialogController(
    private val activity: BaseActivity,
    private val claim: (String, (Boolean) -> Unit) -> Unit,
) {
    private data class Prompt(val campaign: ClaimCampaign, val explicit: Boolean)
    private val pending = linkedMapOf<String, Prompt>()
    private var dialog: AlertDialog? = null
    private var activeKey: String? = null
    private var activeExplicit = false
    private var claimKey: String? = null
    private var result: String? = null
    private var visible = false
    private var inConversation = false

    fun receive(campaigns: List<ClaimCampaign>, fromNotification: Boolean = false) {
        campaigns.forEach {
            if (it.key != activeKey && it.key != claimKey) {
                pending[it.key] = Prompt(it, fromNotification || pending[it.key]?.explicit == true)
            }
        }
        showNext()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) showNext()
    }

    fun setInConversation(value: Boolean) {
        inConversation = value
        if (!value && activeKey != null && !activeExplicit && claimKey == null) dialog?.dismiss()
        showNext()
    }

    fun status(key: String, message: String, finished: Boolean) {
        if (key != claimKey) return
        if (finished) {
            claimKey = null
            result = message
            dialog?.dismiss()
            showNext()
        } else if (visible) ToastUtils.show(activity, message)
    }

    private fun showNext() {
        if (!visible || activity.isFinishing || activity.isDestroyed || dialog != null || claimKey != null) return
        val message = result
        if (message != null) {
            result = null
            show(MaterialAlertDialogBuilder(activity).setTitle("领取结果").setMessage(message)
                .setPositiveButton("完成", null).create())
            return
        }
        val entry = pending.values.firstOrNull { it.explicit || inConversation } ?: return
        val campaign = entry.campaign
        pending.remove(campaign.key)
        activeKey = campaign.key
        activeExplicit = entry.explicit
        val prompt = MaterialAlertDialogBuilder(activity)
            .setTitle(campaign.title)
            .setMessage(campaign.detail)
            .setNegativeButton("稍后", null)
            .setPositiveButton("领取", null)
            .create()
        show(prompt)
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            claimKey = campaign.key
            claim(campaign.key) { accepted ->
                if (accepted) {
                    // Let the WebView's verification popup receive touches.
                    prompt.dismiss()
                } else if (dialog === prompt) {
                    claimKey = null
                    prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    ToastUtils.show(activity, "活动尚未就绪或已结束，请稍后重试")
                }
            }
        }
    }

    private fun show(prompt: AlertDialog) {
        dialog = prompt
        prompt.setOnDismissListener {
            if (dialog === prompt) {
                dialog = null
                activeKey = null
                showNext()
            }
        }
        prompt.show()
        prompt.window?.apply {
            setGravity(Gravity.CENTER)
            // Center in the entire phone display, including the cutout/status-bar inset.
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            attributes = attributes.apply {
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            if (AppSettingsRepository.getInstance(activity).isFullscreenEnabled()) {
                WindowCompat.setDecorFitsSystemWindows(this, false)
                WindowCompat.getInsetsController(this, decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    fun clear() {
        visible = false
        pending.clear()
        claimKey = null
        result = null
        activeKey = null
        inConversation = false
        val previous = dialog
        dialog = null
        previous?.setOnDismissListener(null)
        previous?.dismiss()
    }
}
