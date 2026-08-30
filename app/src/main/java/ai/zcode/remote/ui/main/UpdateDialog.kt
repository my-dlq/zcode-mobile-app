package ai.zcode.remote.ui.main

import ai.zcode.remote.BuildConfig
import ai.zcode.remote.R
import ai.zcode.remote.data.model.UpdateInfo
import ai.zcode.remote.data.repository.UpdateRepository
import ai.zcode.remote.databinding.DialogUpdateBinding
import ai.zcode.remote.utils.ToastUtils
import android.app.Dialog
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.util.Locale

/**
 * 更新对话框：展示新版本信息 → 确认后应用内下载（进度条）→ 完成后拉起系统安装器。
 *
 * 「忽略此版本」写入 UpdateRepository，仅影响启动静默检查；手动检查永远弹窗。
 */
class UpdateDialog : DialogFragment() {

    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!

    private lateinit var updateInfo: UpdateInfo
    private lateinit var downloader: UpdateDownloader

    fun setUpdateInfo(info: UpdateInfo) {
        this.updateInfo = info
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogUpdateBinding.inflate(LayoutInflater.from(context))

        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // 对话框在 Activity 重建（旋转等）后 updateInfo 会丢失，直接随宿主重建流程重新检查，
        // 简化处理：配置变更时关闭对话框即可（手机竖屏应用影响极小）
        if (!::updateInfo.isInitialized) {
            isCancelable = true
            return dialog
        }

        downloader = UpdateDownloader(requireContext().applicationContext)
        initView()
        return dialog
    }

    private fun initView() {
        binding.tvUpdateTitle.text = getString(R.string.update_found_title, updateInfo.versionName)
        binding.tvCurrentVersion.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        binding.tvFileSize.text = getString(R.string.update_file_size, formatFileSize(updateInfo.fileSize))

        if (updateInfo.releaseNotes.isNotBlank()) {
            binding.tvReleaseNotes.text = updateInfo.releaseNotes
            binding.tvReleaseNotes.movementMethod = ScrollingMovementMethod()
        } else {
            binding.tvReleaseNotes.visibility = View.GONE
        }

        binding.btnIgnore.setOnClickListener {
            UpdateRepository.getInstance(requireContext()).setIgnoredVersion(updateInfo.versionName)
            ToastUtils.show(requireContext(), getString(R.string.toast_update_ignored, updateInfo.versionName))
            dismiss()
        }

        binding.btnLater.setOnClickListener { dismiss() }

        binding.btnUpdateNow.setOnClickListener {
            startDownload()
        }

        binding.btnCancelDownload.setOnClickListener {
            downloader.cancel()
            resetToInfoState()
        }
    }

    /** 切换到下载进度态：隐藏信息按钮区，显示进度条与取消按钮 */
    private fun startDownload() {
        binding.layoutButtons.visibility = View.GONE
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.progress = 0

        downloader.download(
            updateInfo,
            listener = { percent, downloadedBytes, totalBytes ->
                binding.progressBar.progress = percent
                binding.tvProgressText.text = getString(
                    R.string.update_downloading,
                    percent,
                    formatFileSize(downloadedBytes),
                    formatFileSize(totalBytes)
                )
            },
            onCompleted = { apkFile ->
                ToastUtils.show(requireContext(), getString(R.string.toast_download_completed))
                downloader.installApk(requireContext(), apkFile)
                dismiss()
            },
            onError = { message ->
                if (message == "cancelled") {
                    resetToInfoState()
                } else {
                    ToastUtils.showLong(requireContext(), getString(R.string.toast_download_failed, message))
                    resetToInfoState()
                }
            }
        )
    }

    /** 下载失败/取消后回到信息展示态，允许用户重试 */
    private fun resetToInfoState() {
        binding.layoutProgress.visibility = View.GONE
        binding.layoutButtons.visibility = View.VISIBLE
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "--"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloader.shutdown()
        _binding = null
    }

    companion object {
        fun newInstance(info: UpdateInfo): UpdateDialog {
            return UpdateDialog().apply {
                setUpdateInfo(info)
            }
        }
    }
}
