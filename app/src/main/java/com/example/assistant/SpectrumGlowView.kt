package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Частотный визуализатор: низкие частоты холодные, высокие — тёплые. */
class SpectrumGlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fft: ByteArray? = null
    private val smooth = FloatArray(48)
    private val rect = RectF()

    fun setFftData(data: ByteArray?) {
        fft = data?.clone()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = fft
        val count = smooth.size
        val gap = width * 0.009f
        val barWidth = (width - gap * (count - 1)) / count
        val base = height * 0.88f
        val maxHeight = height * 0.76f
        for (i in 0 until count) {
            val start = if (data == null) 0 else 2 + (i * (data.size / 2 - 2) / count)
            val end = if (data == null) 0 else min(data.size - 1, start + max(2, data.size / count))
            var energy = 0f
            if (data != null && start < end) {
                for (j in start until end step 2) {
                    val re = data[j].toInt()
                    val im = data.getOrElse(j + 1) { 0 }.toInt()
                    energy += kotlin.math.sqrt((re * re + im * im).toFloat())
                }
                energy = (energy / max(1, (end - start) / 2) / 110f).coerceIn(0f, 1f)
            }
            val wave = (0.10f + 0.08f * (0.5 + 0.5 * sin(i * 0.52 + System.nanoTime() / 1.8e9)).toFloat())
            val target = max(energy, if (data == null) wave else 0.025f)
            smooth[i] += (target - smooth[i]) * 0.32f
            val h = max(3f, maxHeight * smooth[i])
            val x = i * (barWidth + gap)
            val hue = when {
                i < 12 -> 188f - i * 1.3f
                i < 30 -> 220f + (i - 12) * 4.2f
                else -> 296f + (i - 30) * 5.2f
            }
            val color = Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 355f), 0.82f, 1f))
            paint.color = color
            paint.alpha = 65
            paint.setShadowLayer(12f, 0f, 0f, color)
            rect.set(x, base - h, x + barWidth, base)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
            paint.alpha = 255
            paint.clearShadowLayer()
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
        }
        if (data == null) postInvalidateOnAnimation()
    }
}
