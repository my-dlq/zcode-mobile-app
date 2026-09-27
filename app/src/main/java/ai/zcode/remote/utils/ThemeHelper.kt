package ai.zcode.remote.utils

import android.app.Activity
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository

/**
 * 主题应用助手：把用户选择的主题（亮色 / 暗色 / 石墨灰）落到 Activity 上。
 *
 * 背景：项目有**三套**配色，而 AppCompat 的夜间模式只有两态（NO/YES），
 * 因此「石墨灰」不能只靠 `setDefaultNightMode` 表达，需要在 Activity
 * `super.onCreate()` 之前 `setTheme()` 换成石墨灰主题。
 *
 * 映射关系：
 * - LIGHT    → 亮色主题，夜间模式 NO；
 * - DARK     → 暗色主题，夜间模式 YES；
 * - GRAPHITE → 石墨灰主题（颜色严格取自 ZCode 远程页暗色方案），夜间模式 YES
 *              （YES 提供暗色基底，保证 Material 控件的明暗状态正确）。
 *
 * ⚠️ 只影响 App 自身 UI，不改变远程网页背景——远程页外观由 ZCode 自己的主题开关决定。
 */
object ThemeHelper {

    /** Activity onCreate 中、`super.onCreate()` 之前调用。 */
    fun applyTheme(activity: Activity, graphiteThemeRes: Int = R.style.Theme_ZCodeRemote_Graphite) {
        if (AppSettingsRepository.getInstance(activity).getThemeMode() ==
            AppSettingsRepository.ThemeMode.GRAPHITE
        ) {
            activity.setTheme(graphiteThemeRes)
        }
    }

    /**
     * AppCompat 夜间模式取值：只有「亮色」走 NO，其余（含石墨灰）都走 YES。
     * 石墨灰需要暗色基底，否则 Material 控件会停留在亮色形态。
     */
    fun nightModeFor(mode: AppSettingsRepository.ThemeMode): Int =
        if (mode == AppSettingsRepository.ThemeMode.LIGHT) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        } else {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
}
