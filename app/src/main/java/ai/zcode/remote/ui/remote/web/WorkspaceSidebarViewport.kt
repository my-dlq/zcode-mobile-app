package ai.zcode.remote.ui.remote.web

import android.content.res.Configuration
import kotlin.math.roundToInt

/** 横屏手机和平板使用远程工作区的宽屏侧栏，短边仍按屏幕宽度适配。 */
internal object WorkspaceSidebarViewport {
    private const val TABLET_MIN_SW_DP = 600
    private const val WIDE_LAYOUT_MIN_WIDTH = 768

    fun isEligible(configuration: Configuration): Boolean =
        configuration.smallestScreenWidthDp >= TABLET_MIN_SW_DP ||
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun content(screenCssWidth: Int, zoomPercent: Int, eligible: Boolean): String {
        val zoom = zoomPercent / 100f
        val requestedWidth = (screenCssWidth / zoom).roundToInt().coerceAtLeast(1)
        if (eligible && requestedWidth < WIDE_LAYOUT_MIN_WIDTH) {
            val scale = screenCssWidth.toFloat() / WIDE_LAYOUT_MIN_WIDTH
            return "width=$WIDE_LAYOUT_MIN_WIDTH, initial-scale=$scale, maximum-scale=$scale, user-scalable=no"
        }
        if (zoomPercent == 100) {
            return "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
        }
        return "width=$requestedWidth, initial-scale=$zoom, maximum-scale=$zoom, user-scalable=no"
    }
}
