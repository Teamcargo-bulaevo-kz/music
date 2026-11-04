package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Атмосферное свечение за обложкой: неподвижный центр, мягкий радиальный градиент без границ.
 * Бас задаёт яркость и радиус, средние частоты насыщенность и оттенок внутри палитры,
 * высокие слегка добавляют яркость. Все показатели сглажены (EMA): быстро растут, медленно гаснут.
 * Без колец, частиц, волн и вращения.
 */
class AuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Центр свечения по вертикали в пикселях (за обложкой); пока не задан, берётся 40% высоты. */
    var centerYPx = -1f

    private var target = MoodPalette.DEFAULT
    private var baseColor = target.color
    private var brightness = target.brightness
    private var reactivity = target.reactivity
    private var speed = target.speed

    // сглаженные показатели 0..1
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f

    private var lastMs = 0L
    private var attached = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)

    fun setAura(aura: Aura) { target = aura }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; lastMs = 0L; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { attached = false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { lastMs = 0L; postInvalidateOnAnimation() }
    }

    /** EMA с разной скоростью роста и затухания; зависит от времени кадра, а не от частоты кадров. */
    private fun ema(cur: Float, raw: Float, dt: Float, attack: Float, release: Float): Float {
        val rate = if (raw > cur) attack else release
        return cur + (raw - cur) * (1f - Math.exp((-rate * dt).toDouble()).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastMs = now
        BeatSource.tick(PlayerState.playing && MusicService.isRunning)

        // плавный переход палитры и характера (около секунды)
        val k = 1f - Math.exp((-2.5f * dt).toDouble()).toFloat()
        baseColor = ColorUtils.blendARGB(baseColor, target.color, k)
        brightness += (target.brightness - brightness) * k
        reactivity += (target.reactivity - reactivity) * k
        speed += (target.speed - speed) * k

        val sens = AppSettings.beatSensitivity / 100f
        val r = reactivity * sens
        // бас: быстро (около 0.1 с) растёт, медленно (около 0.8 с) гаснет; остальное мягче
        bass = ema(bass, (BeatSource.bass * r).coerceAtMost(1f), dt, 14f * speed.coerceAtLeast(0.6f), 1.3f)
        mid = ema(mid, (BeatSource.mid * r).coerceAtMost(1f), dt, 4f, 1.0f)
        treble = ema(treble, (BeatSource.treble * r).coerceAtMost(1f), dt, 5f, 1.5f)

        // оттенок и насыщенность: средние частоты сдвигают цвет в пределах +-18 градусов
        Color.colorToHSV(baseColor, hsv)
        hsv[0] = (hsv[0] + (mid - 0.5f) * 36f + 360f) % 360f
        hsv[1] = (hsv[1] * (0.75f + 0.25f * mid)).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * (0.85f + 0.15f * treble)).coerceIn(0f, 1f)
        val color = Color.HSVToColor(hsv)

        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(BG)
        val cx = w / 2f
        val cy = if (centerYPx > 0f) centerYPx else h * 0.40f
        val gain = AppSettings.auraBrightness / 100f
        val base = max(w, h * 0.55f)

        // широкое мягкое свечение: радиус и яркость от баса
        val outerR = base * (0.55f + 0.20f * bass)
        val outerA = (gain * brightness * (0.22f + 0.40f * bass + 0.08f * treble)).coerceIn(0f, 0.8f)
        paint.shader = RadialGradient(
            cx, cy, outerR,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, (outerA * 255).toInt()),
                ColorUtils.setAlphaComponent(color, (outerA * 0.45f * 255).toInt()),
                ColorUtils.setAlphaComponent(color, (outerA * 0.12f * 255).toInt()),
                0x00000000
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        if (attached && visibility == VISIBLE && windowVisibility == VISIBLE) postInvalidateDelayed(1000L / AppSettings.fps)
    }

    private companion object {
        const val BG = 0xFF020206.toInt()
    }
}
