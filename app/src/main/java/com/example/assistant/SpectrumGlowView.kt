package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Живой спектр воспроизводимого аудио.
 * Логарифмические полосы частот, быстрая атака и плавный спад — движения следуют
 * реальным изменениям спектра, а не декоративной анимации.
 */
class SpectrumGlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barCount = 64
    private val levels = FloatArray(barCount)
    private val peaks = FloatArray(barCount)
    private val rect = RectF()
    @Volatile private var fft: ByteArray? = null
    private var lastFrameNanos = 0L
    private var beatPulse = 0f
    private var previousBass = 0f
    private var hasSignal = false

    fun setFftData(data: ByteArray?) {
        fft = data?.clone()
        hasSignal = data != null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = fft
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9).toFloat().coerceIn(0.008f, 0.08f)
        lastFrameNanos = now

        val binCount = data?.let { it.size / 2 } ?: 0
        var bass = 0f
        if (data != null && binCount > 4) {
            for (i in 0 until barCount) {
                // Логарифмическая шкала: больше места низким частотам и читаемые верха.
                val low = (1.0 * Math.pow((binCount - 2).toDouble(), i.toDouble() / barCount)).toInt().coerceIn(1, binCount - 2)
                val high = (1.0 * Math.pow((binCount - 2).toDouble(), (i + 1.0) / barCount)).toInt()
                    .coerceIn(low + 1, binCount - 1)
                var sum = 0.0
                for (bin in low until high) {
                    val re = data[bin * 2].toInt()
                    val im = data[bin * 2 + 1].toInt()
                    sum += sqrt((re * re + im * im).toDouble())
                }
                val magnitude = (sum / (high - low)).toFloat()
                val normalized = (ln(1f + magnitude * 1.7f) / ln(1f + 180f * 1.7f)).coerceIn(0f, 1f)
                val target = (normalized * 1.32f).coerceIn(0.015f, 1f)
                val speed = if (target > levels[i]) 0.78f else 0.19f
                levels[i] += (target - levels[i]) * speed
                if (i < 9) bass += levels[i] / 9f
            }
            val rise = (bass - previousBass).coerceAtLeast(0f)
            beatPulse = max(beatPulse * 0.82f, (rise * 2.8f).coerceIn(0f, 1f))
            previousBass = bass
        } else {
            for (i in levels.indices) levels[i] *= 0.88f
            beatPulse *= 0.8f
        }

        val gap = (width * 0.006f).coerceAtLeast(1f)
        val barWidth = (width - gap * (barCount - 1)) / barCount
        val centerY = height * 0.94f
        val maxHeight = height * 0.88f
        val bottomGlow = height * 0.10f

        for (i in 0 until barCount) {
            val x = i * (barWidth + gap)
            val lowBoost = if (i < 12) beatPulse * (1f - i / 14f) * 0.30f else 0f
            val level = (levels[i] + lowBoost).coerceIn(0.018f, 1f)
            val h = (maxHeight * level).coerceAtLeast(height * 0.025f)
            peaks[i] = max(level, peaks[i] - dt * 0.52f)
            val hue = when {
                i < 16 -> 188f + i * 2.0f
                i < 42 -> 220f + (i - 16) * 2.4f
                else -> 282f + (i - 42) * 3.0f
            }.coerceIn(0f, 355f)
            val color = Color.HSVToColor(floatArrayOf(hue, 0.76f, 1f))

            paint.color = color
            paint.alpha = 52
            paint.setShadowLayer(18f + beatPulse * 9f, 0f, 0f, color)
            rect.set(x, centerY - h, x + barWidth, centerY)
            canvas.drawRoundRect(rect, barWidth, barWidth, paint)

            paint.clearShadowLayer()
            paint.alpha = 235
            canvas.drawRoundRect(rect, barWidth, barWidth, paint)

            paint.alpha = 105
            val peakY = centerY - maxHeight * peaks[i]
            canvas.drawRoundRect(x, peakY, x + barWidth, peakY + (height * 0.012f).coerceAtLeast(2f), barWidth, barWidth, paint)
        }

        // Едва заметная световая линия основания.
        paint.clearShadowLayer()
        paint.color = Color.WHITE
        paint.alpha = (18 + beatPulse * 35).toInt()
        canvas.drawRoundRect(0f, centerY + bottomGlow * 0.12f, width.toFloat(), centerY + bottomGlow * 0.22f, 4f, 4f, paint)

        if (hasSignal) postInvalidateOnAnimation()
    }
}
