package com.flo.whisper.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.flo.whisper.R

class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bubbleSize = (56 * resources.displayMetrics.density).toInt()
    private val iconSize = (24 * resources.displayMetrics.density).toInt()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6750A4.toInt() // Material purple
        style = Paint.Style.FILL
    }

    private val recordingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt() // Red
        style = Paint.Style.FILL
    }

    private val processingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFA726.toInt() // Orange
        style = Paint.Style.FILL
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40E53935.toInt() // Transparent red
        style = Paint.Style.FILL
    }

    private var isRecording = false
    private var isProcessing = false
    private var pulseRadius = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val micIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic)?.apply {
        setTint(0xFFFFFFFF.toInt())
    }

    init {
        elevation = 8 * resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = bubbleSize + (bubbleSize * 0.4f).toInt() // Extra space for pulse
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = bubbleSize / 2f

        // Pulse ring when recording
        if (isRecording && pulseRadius > 0) {
            canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
        }

        // Main circle
        val paint = when {
            isProcessing -> processingPaint
            isRecording -> recordingPaint
            else -> basePaint
        }
        canvas.drawCircle(cx, cy, radius, paint)

        // Mic icon
        micIcon?.let {
            val left = (cx - iconSize / 2).toInt()
            val top = (cy - iconSize / 2).toInt()
            it.setBounds(left, top, left + iconSize, top + iconSize)
            it.draw(canvas)
        }
    }

    fun setRecording(recording: Boolean) {
        isRecording = recording
        isProcessing = false
        if (recording) startPulse() else stopPulse()
        invalidate()
    }

    fun setProcessing(processing: Boolean) {
        isProcessing = processing
        isRecording = false
        stopPulse()
        invalidate()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        val maxRadius = bubbleSize / 2f * 1.4f
        pulseAnimator = ValueAnimator.ofFloat(bubbleSize / 2f, maxRadius).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseRadius = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseRadius = 0f
    }
}
