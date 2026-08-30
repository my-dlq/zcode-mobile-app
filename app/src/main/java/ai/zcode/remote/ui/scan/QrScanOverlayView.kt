package ai.zcode.remote.ui.scan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class QrScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        strokeWidth = 10f
        style = Paint.Style.STROKE
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60A5FA")
        strokeWidth = 4f
    }

    val frameRect = RectF()
    private var scanLineProgress = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boxSize = (Math.min(w, h) * 0.7f).coerceAtMost(320f * resources.displayMetrics.density)
        val left = (w - boxSize) / 2f
        val top = (h - boxSize) / 2.2f
        frameRect.set(left, top, left + boxSize, top + boxSize)

        startScanAnimation()
    }

    private fun startScanAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                scanLineProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 绘制暗色蒙版
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // 2. 挖空中间扫描框 (圆角)
        canvas.drawRoundRect(frameRect, 16f, 16f, clearPaint)

        // 3. 绘制四角加粗高亮线
        val cornerLength = 36f
        val radius = 16f

        // 左上
        canvas.drawLine(frameRect.left, frameRect.top + cornerLength, frameRect.left, frameRect.top + radius, cornerPaint)
        canvas.drawLine(frameRect.left, frameRect.top, frameRect.left + cornerLength, frameRect.top, cornerPaint)

        // 右上
        canvas.drawLine(frameRect.right - cornerLength, frameRect.top, frameRect.right, frameRect.top, cornerPaint)
        canvas.drawLine(frameRect.right, frameRect.top, frameRect.right, frameRect.top + cornerLength, cornerPaint)

        // 左下
        canvas.drawLine(frameRect.left, frameRect.bottom - cornerLength, frameRect.left, frameRect.bottom, cornerPaint)
        canvas.drawLine(frameRect.left, frameRect.bottom, frameRect.left + cornerLength, frameRect.bottom, cornerPaint)

        // 右下
        canvas.drawLine(frameRect.right - cornerLength, frameRect.bottom, frameRect.right, frameRect.bottom, cornerPaint)
        canvas.drawLine(frameRect.right, frameRect.bottom - cornerLength, frameRect.right, frameRect.bottom, cornerPaint)

        // 4. 绘制上下移动的扫描线
        val scanY = frameRect.top + (frameRect.height() * scanLineProgress)
        canvas.drawLine(frameRect.left + 10f, scanY, frameRect.right - 10f, scanY, scanLinePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
