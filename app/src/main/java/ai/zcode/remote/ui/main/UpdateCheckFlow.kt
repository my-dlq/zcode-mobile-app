package ai.zcode.remote.ui.main

import ai.zcode.remote.BuildConfig
import ai.zcode.remote.R
import ai.zcode.remote.data.model.UpdateInfo
import ai.zcode.remote.data.repository.UpdateRepository
import ai.zcode.remote.utils.ToastUtils
import ai.zcode.remote.utils.UpdateChecker
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 检查更新的共用流程（GitHub Releases）：
 * - manual=true 手动检查：结果无论有无都给出反馈；
 * - manual=false 启动静默检查：受悬浮面板「启动时检查更新」开关控制，
 *   仅在发现未被忽略的新版本时弹窗，其余情况完全无感知。
 *
 * 每个页面持有自己的实例，onDestroy 时调用 [shutdown]。
 */
class UpdateCheckFlow(private val activity: FragmentActivity) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun check(manual: Boolean) {
        val updateRepository = UpdateRepository.getInstance(activity)
        if (!manual && !updateRepository.isAutoCheckEnabled()) return

        executor.execute {
            val info = UpdateChecker.checkLatestRelease()
            mainHandler.post {
                // Activity 可能已销毁（异步回调），避免泄漏与崩溃
                if (activity.isDestroyed || activity.isFinishing) return@post

                when {
                    info == null -> {
                        if (manual) ToastUtils.show(activity, activity.getString(R.string.toast_check_update_failed))
                    }
                    UpdateChecker.compareVersion(BuildConfig.VERSION_NAME, info.versionName) >= 0 -> {
                        if (manual) ToastUtils.show(activity, activity.getString(R.string.toast_already_latest))
                    }
                    !manual && info.versionName == updateRepository.getIgnoredVersion() -> {
                        // 静默模式下用户已忽略该版本，不打扰
                    }
                    else -> showUpdateDialog(info)
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        UpdateDialog.newInstance(info).show(activity.supportFragmentManager, "UpdateDialog")
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
