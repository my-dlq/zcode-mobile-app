package ai.zcode.remote.ui.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.UpdateRepository
import ai.zcode.remote.databinding.DialogFloatingMenuBinding

class FloatingControlDialog : BottomSheetDialogFragment() {

    private var _binding: DialogFloatingMenuBinding? = null
    private val binding get() = _binding!!

    private var deviceName: String = ""
    private var isFullscreen: Boolean = true

    var onOpenSettingsListener: (() -> Unit)? = null
    var onRefreshListener: (() -> Unit)? = null
    var onToggleFullscreenListener: (() -> Unit)? = null
    var onCopyUrlListener: (() -> Unit)? = null
    var onClearCacheListener: (() -> Unit)? = null
    var onBackHomeListener: (() -> Unit)? = null

    fun setInitialState(
        deviceName: String,
        isFullscreen: Boolean,
    ) {
        this.deviceName = deviceName
        this.isFullscreen = isFullscreen
    }

    override fun getTheme(): Int = R.style.Theme_ZCodeRemote_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFloatingMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        // deviceName 缺省时回退到本地化的默认文案，避免首次创建对话框拿到空串
        binding.tvMenuDeviceName.text =
            deviceName.ifBlank { getString(R.string.default_device_name) }

        // 状态文字与图标
        binding.tvFullscreenText.text = getString(
            if (isFullscreen) R.string.floating_fullscreen_exit else R.string.floating_fullscreen_enter
        )

        binding.btnMenuClose.setOnClickListener { dismiss() }

        binding.menuItemWorkspaceSettings.setOnClickListener {
            dismiss()
            onOpenSettingsListener?.invoke()
        }

        binding.menuItemRefresh.setOnClickListener {
            dismiss()
            onRefreshListener?.invoke()
        }

        binding.menuItemFullscreen.setOnClickListener {
            dismiss()
            onToggleFullscreenListener?.invoke()
        }

        binding.menuItemCopyUrl.setOnClickListener {
            dismiss()
            onCopyUrlListener?.invoke()
        }

        binding.menuItemClearCache.setOnClickListener {
            dismiss()
            onClearCacheListener?.invoke()
        }

        // 启动静默检查更新开关：直接落库持久化，行点击与 switch 自身都能切换
        val updateRepository = UpdateRepository.getInstance(requireContext())
        binding.switchAutoCheckUpdate.isChecked = updateRepository.isAutoCheckEnabled()
        binding.switchAutoCheckUpdate.setOnCheckedChangeListener { _, isChecked ->
            updateRepository.setAutoCheckEnabled(isChecked)
        }
        binding.menuItemAutoCheckUpdate.setOnClickListener {
            binding.switchAutoCheckUpdate.toggle()
        }

        binding.menuItemBackHome.setOnClickListener {
            dismiss()
            onBackHomeListener?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            deviceName: String,
            isFullscreen: Boolean,
        ): FloatingControlDialog {
            return FloatingControlDialog().apply {
                setInitialState(deviceName, isFullscreen)
            }
        }
    }
}
