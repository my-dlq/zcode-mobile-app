package ai.zcode.remote.ui.remote

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import ai.zcode.remote.R

class FloatingControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var dX = 0f
    private var dY = 0f
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var isHalfHidden = false
    private var snappedEdge: Edge? = null

    private val hideRunnable = Runnable {
        val parentView = parent as? ViewGroup ?: return@Runnable
        snappedEdge?.let { hideHalfAtEdge(parentView, it) }
    }

    private enum class Edge {
        LEFT, RIGHT, TOP, BOTTOM
    }

    var onClickAction: (() -> Unit)? = null

    init {
        val touchSize = (48 * density).toInt()
        val iconSize = (24 * density).toInt()
        val padding = (5 * density).toInt()

        minimumWidth = touchSize
        minimumHeight = touchSize

        val imageView = ImageView(context).apply {
            layoutParams = LayoutParams(iconSize, iconSize, android.view.Gravity.CENTER)
            background = ContextCompat.getDrawable(context, R.drawable.bg_floating_ball)
            setImageResource(R.drawable.ic_floating_anchor)
            setPadding(padding, padding, padding, padding)
            elevation = 6 * density
        }

        addView(imageView)
        alpha = 0.75f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? ViewGroup ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parentView.removeCallbacks(hideRunnable)
                if (isHalfHidden) {
                    restoreFromEdge(parentView)
                }
                alpha = 1.0f
                startX = event.rawX
                startY = event.rawY
                dX = x - event.rawX
                dY = y - event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val distance = Math.hypot(
                    (event.rawX - startX).toDouble(),
                    (event.rawY - startY).toDouble()
                )
                if (distance > touchSlop) {
                    isDragging = true
                }

                if (isDragging) {
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY
                    val maxX = (parentView.width - width).toFloat().coerceAtLeast(0f)
                    val maxY = (parentView.height - height).toFloat().coerceAtLeast(0f)
                    newX = newX.coerceIn(0f, maxX)
                    newY = newY.coerceIn(0f, maxY)
                    x = newX
                    y = newY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onClickAction?.invoke()
                } else {
                    handleDragRelease(parentView)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    handleDragRelease(parentView)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDragRelease(parentView: ViewGroup) {
        // 保留用户拖动后的精确位置，不再强制吸附到最近边缘。
        // 只有真正贴近四条边时才启动半隐藏计时。
        snappedEdge = edgeAtCurrentPosition(parentView)
        if (snappedEdge != null) {
            scheduleHalfHide(parentView)
        } else {
            parentView.removeCallbacks(hideRunnable)
        }
    }

    private fun edgeAtCurrentPosition(parentView: ViewGroup): Edge? {
        val threshold = (2 * density).coerceAtLeast(1f)
        val maxX = (parentView.width - width).toFloat().coerceAtLeast(0f)
        val maxY = (parentView.height - height).toFloat().coerceAtLeast(0f)
        return when {
            x <= threshold -> Edge.LEFT
            x >= maxX - threshold -> Edge.RIGHT
            y <= threshold -> Edge.TOP
            y >= maxY - threshold -> Edge.BOTTOM
            else -> null
        }
    }

    private fun fullEdgePosition(parentView: ViewGroup, edge: Edge): Pair<Float, Float> {
        val maxX = (parentView.width - width).toFloat().coerceAtLeast(0f)
        val maxY = (parentView.height - height).toFloat().coerceAtLeast(0f)
        return when (edge) {
            Edge.LEFT -> 0f to y.coerceIn(0f, maxY)
            Edge.RIGHT -> maxX to y.coerceIn(0f, maxY)
            Edge.TOP -> x.coerceIn(0f, maxX) to 0f
            Edge.BOTTOM -> x.coerceIn(0f, maxX) to maxY
        }
    }

    private fun hideHalfAtEdge(parentView: ViewGroup, edge: Edge) {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val target = when (edge) {
            Edge.LEFT -> -halfWidth to y
            Edge.RIGHT -> (parentView.width - halfWidth) to y
            Edge.TOP -> x to -halfHeight
            Edge.BOTTOM -> x to (parentView.height - halfHeight)
        }
        isHalfHidden = true
        animateTo(target.first, target.second)
    }

    private fun restoreFromEdge(parentView: ViewGroup) {
        val edge = snappedEdge ?: return
        animate().cancel()
        val target = fullEdgePosition(parentView, edge)
        x = target.first
        y = target.second
        isHalfHidden = false
    }

    private fun scheduleHalfHide(parentView: ViewGroup) {
        parentView.removeCallbacks(hideRunnable)
        parentView.postDelayed(hideRunnable, 5000L)
    }

    private fun animateTo(targetX: Float, targetY: Float) {
        animate().cancel()
        val startX = x
        val startY = y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                x = startX + (targetX - startX) * progress
                y = startY + (targetY - startY) * progress
            }
            start()
        }
    }
}
