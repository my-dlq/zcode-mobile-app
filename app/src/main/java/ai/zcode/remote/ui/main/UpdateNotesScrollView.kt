package ai.zcode.remote.ui.main

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/** 短说明随内容收缩，长说明最多占屏幕高度的 38%，为更新按钮保留空间。 */
class UpdateNotesScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = resources.displayMetrics
        val limit = minOf((320 * metrics.density).toInt(), (metrics.heightPixels * 0.38f).toInt())
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            limit
        } else {
            minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }
}
