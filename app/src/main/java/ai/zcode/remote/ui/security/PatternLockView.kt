package ai.zcode.remote.ui.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import ai.zcode.remote.R
import kotlin.math.hypot

/** 简洁的 3x3 滑动图案控件，输出 0~8 的节点序列。 */
class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPatternComplete: ((List<Int>) -> Unit)? = null

    private val centers = Array(9) { floatArrayOf(0f, 0f) }
    private val selected = mutableListOf<Int>()
    private var currentX = 0f
    private var currentY = 0f
    private var tracking = false
    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val accent = ContextCompat.getColor(context, R.color.primary)
        val muted = ContextCompat.getColor(context, R.color.text_muted)
        val selectedFill = ContextCompat.getColor(context, R.color.primary_light)
        ringPaint.color = muted
        linePaint.color = accent

        val path = Path()
        selected.forEachIndexed { index, node ->
            val point = centers[node]
            if (index == 0) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
        }
        if (tracking && selected.isNotEmpty()) path.lineTo(currentX, currentY)
        if (!path.isEmpty) canvas.drawPath(path, linePaint)

        val ringRadius = 27f * density
        val dotRadius = 7f * density
        for (index in 0..8) {
            val point = centers[index]
            val active = selected.contains(index)
            ringPaint.color = if (active) accent else muted
            fillPaint.color = if (active) selectedFill else ContextCompat.getColor(context, R.color.bg_surface_elevated)
            canvas.drawCircle(point[0], point[1], ringRadius, ringPaint)
            canvas.drawCircle(point[0], point[1], if (active) dotRadius + 2f * density else dotRadius, fillPaint)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val horizontal = width / 2f
        val vertical = height / 2f
        val spacing = minOf(width, height) / 3.2f
        for (row in 0..2) for (column in 0..2) {
            val index = row * 3 + column
            centers[index][0] = horizontal + (column - 1) * spacing
            centers[index][1] = vertical + (row - 1) * spacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                tracking = true
                currentX = event.x
                currentY = event.y
                findNode(event.x, event.y)?.let { selected.add(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                currentX = event.x
                currentY = event.y
                findNode(event.x, event.y)?.let { node ->
                    if (!selected.contains(node)) {
                        addCrossedNodeIfNeeded(selected.lastOrNull(), node)
                        selected.add(node)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    currentX = event.x
                    currentY = event.y
                    invalidate()
                    if (selected.size >= 4) onPatternComplete?.invoke(selected.toList())
                }
                return true
            }
        }
        return true
    }

    fun reset() {
        selected.clear()
        tracking = false
        invalidate()
    }

    private fun findNode(x: Float, y: Float): Int? {
        val threshold = 42f * density
        return centers.indices
            .filterNot { selected.contains(it) }
            .minByOrNull { hypot((centers[it][0] - x).toDouble(), (centers[it][1] - y).toDouble()) }
            ?.takeIf { hypot((centers[it][0] - x).toDouble(), (centers[it][1] - y).toDouble()) <= threshold }
    }

    private fun addCrossedNodeIfNeeded(from: Int?, to: Int) {
        if (from == null) return
        val fromRow = from / 3
        val fromColumn = from % 3
        val toRow = to / 3
        val toColumn = to % 3
        if ((fromRow + toRow) % 2 == 0 && (fromColumn + toColumn) % 2 == 0) {
            val middle = ((fromRow + toRow) / 2) * 3 + (fromColumn + toColumn) / 2
            if (!selected.contains(middle)) selected.add(middle)
        }
    }

}
