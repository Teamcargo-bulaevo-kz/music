package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Плавный спектр воспроизводимого аудио.
 * Низкие частоты распределены отдельно, чтобы первые столбцы не слипались
 * в одинаковые FFT-бинны; верхний диапазон уплотняется логарифмически.
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
    private var spectrumGradient: LinearGradient? = null

    fun setFftData(data: ByteArray?) {
        fft = data?.clone()
        hasSignal = data != null
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        spectrumGradient = if (w > 0) LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.rgb(62, 205, 235),
                Color.rgb(91, 145, 255),
                Color.rgb(145, 112, 255),
                Color.rgb(205, 112, 239),
                Color.rgb(255, 139, 190),
                Color.rgb(255, 169, 126)
            ),
            floatArrayOf(0f, 0.20f, 0.43f, 0.67f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        ) else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = fft
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f
            else ((now - lastFrameNanos) / 1e9).toFloat().coerceIn(0.008f, 0.06f)
        lastFrameNanos = now

        val binCount = data?.let { it.size / 2 } ?: 0
        var bass = 0f
        if (data != null && binCount > 8) {
            val lastBin = binCount - 1
            for (i in 0 until barCount) {
                // Первые 16 столбцов получают отдельные низкие бины без повторов.
                val low: Int
                val high: Int
                if (i < 16) {
                    low = (1 + i * 2).coerceAtMost(lastBin - 1)
                    high = (low + 2).coerceAtMost(lastBin)
                } else {
                    val t0 = (i - 16f) / (barCount - 16f)
                    val t1 = (i - 15f) / (barCount - 16f)
                    val range = (lastBin - 32).coerceAtLeast(1).toDouble()
                    low = (32.0 * Math.pow(range / 32.0, t0.toDouble())).toInt()
                        .coerceIn(32, lastBin - 1)
                    high = (32.0 * Math.pow(range / 32.0, t1.toDouble())).toInt()
                        .coerceIn(low + 1, lastBin)
                }

                var sum = 0.0
                var peak = 0.0
                for (bin in low until high) {
                    val re = data[bin * 2].toInt()
                    val im = data[bin * 2 + 1].toInt()
                    val magnitude = sqrt((re * re + im * im).toDouble())
                    sum += magnitude
                    if (magnitude > peak) peak = magnitude
                }
                // Пик помогает не терять узкие частотные всплески, среднее убирает резкость.
                val magnitude = ((sum / (high - low)) * 0.72 + peak * 0.28).toFloat()
                val frequencyPosition = i.toFloat() / (barCount - 1)
                val highFrequencyLift = 1f + frequencyPosition * 1.05f
                val compressed = ln(1f + magnitude * highFrequencyLift * 2.1f) /
                    ln(1f + 105f * 2.1f)
                val target = (compressed * 1.18f).coerceIn(0.012f, 1f)

                // Временные константы вместо резкого скачка: мягкая атака и длинный спад.
                val timeConstant = if (target > levels[i]) 0.115f else 0.34f
                val smoothing = (1f - exp(-dt / timeConstant)).coerceIn(0f, 1f)
                levels[i] += (target - levels[i]) * smoothing
                if (i < 12) bass += levels[i] / 12f
            }
            val rise = (bass - previousBass).coerceAtLeast(0f)
            beatPulse += ((rise * 1.8f).coerceIn(0f, 0.22f) - beatPulse) *
                (1f - exp(-dt / 0.22f))
            previousBass = bass
        } else {
            for (i in levels.indices) {
                levels[i] *= (1f - exp(-dt / 0.38f))
            }
            beatPulse *= (1f - exp(-dt / 0.28f))
        }

        val gap = (width * 0.006f).coerceAtLeast(1f)
        val barWidth = (width - gap * (barCount - 1)) / barCount
        val baseline = height * 0.94f
        val maxHeight = height * 0.86f
        val gradient = spectrumGradient

        if (gradient != null) {
            paint.shader = gradient
        } else {
            paint.color = Color.CYAN
        }

        for (i in 0 until barCount) {
            val x = i * (barWidth + gap)
            val bassLift = if (i < 12) beatPulse * (1f - i / 14f) * 0.20f else 0f
            val level = (levels[i] + bassLift).coerceIn(0.012f, 1f)
            val barHeight = (maxHeight * level).coerceAtLeast(height * 0.022f)
            peaks[i] = max(level, peaks[i] - dt * 0.42f)
            rect.set(x, baseline - barHeight, x + barWidth, baseline)

            // Рассеянное свечение под основным столбцом.
            paint.alpha = 42
            paint.setShadowLayer(13f + beatPulse * 5f, 0f, 0f, Color.rgb(145, 112, 255))
            canvas.drawRoundRect(rect, barWidth, barWidth, paint)

            // Чёткое ядро с непрерывным цветовым градиентом по всей ширине.
            paint.clearShadowLayer()
            paint.alpha = 225
            canvas.drawRoundRect(rect, barWidth, barWidth, paint)

            paint.alpha = 82
            val peakY = baseline - maxHeight * peaks[i]
            canvas.drawRoundRect(
                x, peakY, x + barWidth,
                peakY + (height * 0.010f).coerceAtLeast(2f),
                barWidth, barWidth, paint
            )
        }

        paint.clearShadowLayer()
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = (12 + beatPulse * 28).toInt()
        canvas.drawRoundRect(
            0f, baseline + height * 0.012f, width.toFloat(),
            baseline + height * 0.024f, 4f, 4f, paint
        )

        if (hasSignal) postInvalidateOnAnimation()
    }
}
