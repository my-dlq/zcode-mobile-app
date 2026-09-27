package ai.zcode.remote.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.utils.ThemeHelper

/**
 * 统一处理主题应用的基类。
 *
 * 背景：项目有亮色 / 暗色 / 石墨灰三套配色，而 AppCompat 夜间模式只有亮/暗两态，
 * 石墨灰必须靠 Activity `super.onCreate()` 前 `setTheme()` 落地。
 *
 * 这里解决两类状态残留：
 * 1. 「无法切换」——DARK 与 GRAPHITE 都映射到 MODE_NIGHT_YES，切换时 AppCompat
 *    认为夜间模式没变，不会自动重建任何 Activity，旧主题会一直残留。
 * 2. 「颜色混搭」——从石墨灰切回暗色时，若没有显式重置主题，Activity 仍带着
 *    石墨灰的 `zc*` 属性值，而 AppCompat 已是暗色基底，于是两套颜色混在一起。
 *
 * 机制：每个 Activity 在 onCreate 记录创建时的主题，onResume 时与当前偏好对比，
 * 不一致就 recreate()。这样无论用户从哪个页面改了主题，返回栈里所有 Activity
 * 回到前台时都会自动重建，拿到一致的新主题。
 */
abstract class BaseActivity : AppCompatActivity() {

    /** 本 Activity 创建时实际应用的主题，用于 onResume 检测是否需要重建。 */
    private var appliedThemeMode: AppSettingsRepository.ThemeMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeInternal()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // 主题可能在其他页面被修改：若与当前偏好不一致则重建
        val current = AppSettingsRepository.getInstance(this).getThemeMode()
        if (appliedThemeMode != null && appliedThemeMode != current) {
            recreate()
        }
    }

    /** 在 `super.onCreate()` 前应用主题；子类可重写以提供全屏变体。 */
    protected open fun applyThemeInternal() {
        val mode = AppSettingsRepository.getInstance(this).getThemeMode()
        ThemeHelper.applyTheme(this, graphiteThemeRes())
        appliedThemeMode = mode
    }

    /** 石墨灰主题资源；全屏页可重写返回全屏变体。 */
    protected open fun graphiteThemeRes(): Int = ai.zcode.remote.R.style.Theme_ZCodeRemote_Graphite
}
