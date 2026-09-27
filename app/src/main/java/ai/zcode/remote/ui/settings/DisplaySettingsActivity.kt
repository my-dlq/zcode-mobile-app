package ai.zcode.remote.ui.settings

import ai.zcode.remote.ui.BaseActivity

import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.databinding.ActivityDisplaySettingsBinding
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar

/**
 * 显示与全屏设置页：集中管理远程连接页的显示方式。
 *
 * 四个设置项：
 * - **全屏**：隐藏系统状态栏与导航栏（沉浸式）；
 * - **显示顶部栏**：远程连接页是否展示 App 自己的顶栏（LOGO + 菜单）；
 * - **页面缩放**：远程页面整体显示比例；
 * - **工作区与任务页样式**：该页用远程原生界面还是 Zmobile 移动适配界面。
 *
 * 前两项相互独立；后两项同样独立。改动在远程页 onResume 时生效
 * （RemoteControlActivity 每次回到前台按最新设置重设/重注），因此从本页返回即可看到效果。
 */
class DisplaySettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityDisplaySettingsBinding
    private lateinit var appSettings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDisplaySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettingsRepository.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        // 点整行 = 拨动开关（与安全设置页交互一致）
        binding.rowFullscreen.setOnClickListener { binding.switchFullscreen.toggle() }
        binding.rowTopBar.setOnClickListener { binding.switchTopBar.toggle() }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::appSettings.isInitialized) {
            refreshState()
        }
    }

    private fun refreshState() {
        val fullscreen = appSettings.isFullscreenEnabled()
        binding.switchFullscreen.setOnCheckedChangeListener(null)
        binding.switchFullscreen.isChecked = fullscreen
        binding.switchFullscreen.setOnCheckedChangeListener { _, checked ->
            appSettings.setFullscreenEnabled(checked)
            updateFullscreenSummary(checked)
        }
        updateFullscreenSummary(fullscreen)

        val topBar = appSettings.isRemoteTopBarVisible()
        binding.switchTopBar.setOnCheckedChangeListener(null)
        binding.switchTopBar.isChecked = topBar
        binding.switchTopBar.setOnCheckedChangeListener { _, checked ->
            appSettings.setRemoteTopBarVisible(checked)
            updateTopBarSummary(checked)
        }
        updateTopBarSummary(topBar)

        refreshZoomState()
        // ⚠️ 「工作区与任务页样式」本版本按需求隐藏（布局里 rowDashboard/dividerDashboard
        //    均为 gone），故不初始化它的下拉。refreshDashboardModeState() 完整保留，
        //    后续版本恢复时：删掉布局里那两处 visibility="gone"，并在此重新调用即可。
        //    该页当前固定走「远程原生」（AppSettingsRepository 的默认值）。
    }

    /**
     * 「工作区与任务页样式」下拉：远程原生 / Zmobile移动适配。
     * 选中即落盘；远程页在返回时（RemoteControlActivity.onResume）按新配置重注样式。
     *
     * 本版本暂未启用（见 refreshState 中的说明）。
     */
    private fun refreshDashboardModeState() {
        val modes = AppSettingsRepository.DashboardMode.entries
        val labels = modes.map { dashboardModeLabel(it) }
        binding.ddDashboardMode.setSimpleItems(labels.toTypedArray())
        binding.ddDashboardMode.setText(dashboardModeLabel(appSettings.getDashboardMode()), false)
        binding.ddDashboardMode.setOnItemClickListener { _, _, position, _ ->
            appSettings.setDashboardMode(modes[position])
        }
    }

    private fun dashboardModeLabel(mode: AppSettingsRepository.DashboardMode): String = getString(
        when (mode) {
            AppSettingsRepository.DashboardMode.NATIVE -> R.string.settings_dashboard_native
            AppSettingsRepository.DashboardMode.ADAPTIVE -> R.string.settings_dashboard_adaptive
        },
    )

    /**
     * 页面缩放滑杆：progress 0~100 映射到 50%~150%（max 与范围常量联动，改范围时同步布局 XML 的 android:max）。
     * 拖动过程中只更新百分比文案，松手（onStopTrackingTouch）才落盘，
     * 避免滑杆连续回调里反复 apply() 写 SharedPreferences。
     */
    private fun refreshZoomState() {
        val zoom = appSettings.getPageZoom()
        binding.seekZoom.max = AppSettingsRepository.PAGE_ZOOM_MAX - AppSettingsRepository.PAGE_ZOOM_MIN
        binding.seekZoom.setOnSeekBarChangeListener(null)
        binding.seekZoom.progress = zoom - AppSettingsRepository.PAGE_ZOOM_MIN
        binding.tvZoomValue.text = getString(R.string.settings_zoom_value, zoom)
        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = AppSettingsRepository.PAGE_ZOOM_MIN + progress
                binding.tvZoomValue.text = getString(R.string.settings_zoom_value, percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val percent = AppSettingsRepository.PAGE_ZOOM_MIN + (seekBar?.progress ?: 0)
                appSettings.setPageZoom(percent)
            }
        })
    }

    private fun updateFullscreenSummary(enabled: Boolean) {
        binding.tvFullscreenSummary.setText(
            if (enabled) R.string.settings_fullscreen_on else R.string.settings_fullscreen_off
        )
    }

    private fun updateTopBarSummary(visible: Boolean) {
        binding.tvTopBarSummary.setText(
            if (visible) R.string.settings_topbar_on else R.string.settings_topbar_off
        )
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DisplaySettingsActivity::class.java))
        }
    }
}
