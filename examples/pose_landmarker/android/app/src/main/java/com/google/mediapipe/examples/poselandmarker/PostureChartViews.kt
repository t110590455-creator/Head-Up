package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class PosturePieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.headup_card_stroke)
    }
    private var safeSeconds = 0L
    private var warningSeconds = 0L
    private var dangerSeconds = 0L

    fun setData(safe: Long, warning: Long, danger: Long) {
        safeSeconds = safe
        warningSeconds = warning
        dangerSeconds = danger
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 18f * resources.displayMetrics.density
        val size = minOf(width, height).toFloat() - stroke * 1.5f
        val oval = RectF(
            (width - size) / 2f,
            (height - size) / 2f,
            (width + size) / 2f,
            (height + size) / 2f,
        )
        canvas.drawOval(oval, trackPaint)
        val total = safeSeconds + warningSeconds + dangerSeconds
        if (total <= 0L) return

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.BUTT
        var start = -90f
        listOf(
            safeSeconds to R.color.headup_safe,
            warningSeconds to R.color.headup_warning,
            dangerSeconds to R.color.headup_danger,
        ).forEach { (value, colorRes) ->
            val sweep = value.toFloat() / total.toFloat() * 360f
            paint.color = ContextCompat.getColor(context, colorRes)
            canvas.drawArc(oval, start, sweep, false, paint)
            start += sweep
        }
    }
}

class PostureLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.headup_danger)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.headup_danger)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.headup_card_stroke)
    }
    private var values: List<Int> = emptyList()

    fun setData(dangerEvents: List<Int>) {
        values = dangerEvents
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 18f * resources.displayMetrics.density
        val chartWidth = (width - padding * 2f).coerceAtLeast(1f)
        val chartHeight = (height - padding * 2f).coerceAtLeast(1f)
        canvas.drawLine(padding, height - padding, width - padding, height - padding, axisPaint)
        if (values.isEmpty()) return

        val maxValue = max(1, values.maxOrNull() ?: 1)
        val stepX = if (values.size == 1) 0f else chartWidth / (values.size - 1)
        var previousX = padding
        var previousY = height - padding - values.first().toFloat() / maxValue * chartHeight
        values.forEachIndexed { index, value ->
            val x = padding + index * stepX
            val y = height - padding - value.toFloat() / maxValue * chartHeight
            if (index > 0) canvas.drawLine(previousX, previousY, x, y, linePaint)
            canvas.drawCircle(x, y, 4.5f * resources.displayMetrics.density, pointPaint)
            previousX = x
            previousY = y
        }
    }
}
