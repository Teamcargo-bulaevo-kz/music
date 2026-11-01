package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Живой фон: тёмно-синий и фиолетовый градиент, в котором медленно переливаются пятна цвета,
 * а снизу вверх тянется плавное пламя. Высота пламени и яркость пятен зависят от ритма ([BeatSource]).
 */
class FlameBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 1 для списка (спокойнее, чтобы не мешать тексту), больше для экрана плеера. */
    var boost = 1f

    private val basePaint = Paint()
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val path = Path()

    private val palette = intArrayOf(
        0xFF2447FF.toInt(), // синий
        0xFF4A3CFF.toInt(), // индиго
        0xFF7B3DFF.toInt(), // фиолетовый
        0xFFA24BFF.toInt(), // светлая сирень
        0xFF2A6BFF.toInt(), // голубой
    )

    private var time = 0f
    private var lastFrame = 0L
    private var attached = false
    private var smoothBass = 0f
    private var smoothLevel = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF05071A.toInt(), 0xFF0A0F33.toInt(), 0xFF120A38.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && attached) {
            lastFrame = 0L
            postInvalidateOnAnimation()
        }
    }

    private fun mix(phase: Float): Int {
        val p = ((phase % 1f) + 1f) % 1f * palette.size
        val i = p.toInt() % palette.size
        val f = p - p.toInt()
        return ColorUtils.blendARGB(palette[i], palette[(i + 1) % palette.size], f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now

        BeatSource.tick(PlayerState.playing && MusicService.isRunning)
        smoothBass += (BeatSource.bass - smoothBass) * min(1f, dt * 14f)
        smoothLevel += (BeatSource.level - smoothLevel) * min(1f, dt * 8f)
        // чем громче и ритмичнее, тем быстрее переливаются цвета
        time += dt * (0.35f + smoothLevel * 1.1f + smoothBass * 0.6f)

        canvas.drawRect(0f, 0f, w, h, basePaint)

        // переливающиеся пятна цвета
        val big = max(w, h)
        for (i in 0 until 3) {
            val cx = w * (0.5f + 0.42f * sin(time * 0.7f + i * 2.1f))
            val cy = h * (0.5f + 0.38f * cos(time * 0.5f + i * 1.7f))
            val r = big * (0.42f + 0.10f * i + 0.28f * smoothBass * boost)
            val alpha = ((0.16f + 0.40f * smoothLevel + 0.18f * smoothBass) * boost).coerceIn(0.10f, 0.75f)
            val color = ColorUtils.setAlphaComponent(mix(time * 0.06f + i * 0.31f), (alpha * 255).toInt())
            blobPaint.shader = RadialGradient(cx, cy, r, color, ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, blobPaint)
        }

        // пламя: три слоя языков, снизу вверх
        val step = max(8f, w / 28f)
        for (layer in 0 until 3) {
            val reach = (0.10f + 0.20f * smoothBass + 0.10f * smoothLevel) * boost * (1f - 0.22f * layer)
            val flameH = h * reach.coerceIn(0.06f, 0.55f)
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + step) {
                val nx = x / w
                val tongue = abs(sin(nx * 9.0f + time * 2.4f + layer * 1.3f)) * 0.55f +
                    abs(sin(nx * 5.0f - time * 1.7f + layer * 1.9f)) * 0.45f
                val y = h - flameH * (0.35f + 0.65f * tongue)
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w + step, h)
            path.close()
            val bottom = ColorUtils.setAlphaComponent(mix(time * 0.05f + layer * 0.27f + 0.15f), (0.80f * 255).toInt())
            val top = ColorUtils.setAlphaComponent(bottom, 0)
            flamePaint.shader = LinearGradient(0f, h - flameH, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawPath(path, flamePaint)
        }

        if (attached && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
