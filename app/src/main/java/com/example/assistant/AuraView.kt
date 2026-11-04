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

    /** Радиус круглой обложки в пикселях: цветной ореол начинается от её края. */
    var coverRadiusPx = 0f

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

        // Основной оттенок палитры; средние частоты сдвигают его на +-18 градусов
        Color.colorToHSV(baseColor, hsv)
        val hue = (hsv[0] + (mid - 0.5f) * 36f + 360f) % 360f
        val sat = (0.80f + 0.20f * mid).coerceIn(0f, 1f)
        // парный оттенок: уводит в сторону фиолетового/пурпурного, как на эталоне (красный справа, фиолетовый слева)
        val hue2 = if (hue >= 180f) (hue + 50f) % 360f else (hue - 50f + 360f) % 360f
        val hueAccent = (hue + 25f) % 360f
        fun tone(hh: Float, v: Float) = Color.HSVToColor(floatArrayOf(hh, sat, v.coerceIn(0f, 1f)))
        val main = tone(hue, 1f)
        val second = tone(hue2, 0.95f)
        val accent = ColorUtils.blendARGB(tone(hueAccent, 1f), 0xFFFFFFFF.toInt(), 0.15f + 0.20f * treble)

        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(BG)
        val cx = w / 2f
        val cy = if (centerYPx > 0f) centerYPx else h * 0.40f
        val gain = AppSettings.auraBrightness / 100f
        val cr = if (coverRadiusPx > 0f) coverRadiusPx else w * 0.25f
        // яркость: бас делает удар заметным, но основа остаётся яркой даже на спокойной музыке
        val a = (gain * (0.55f + 0.45f * brightness) * (0.70f + 0.30f * bass) + 0.06f * treble).coerceIn(0f, 1f)
        val outerR = cr * (2.7f + 0.8f * bass)

        paint.xfermode = ADD
        blob(canvas, cx - 0.40f * cr, cy + 0.15f * cr, outerR * 0.95f, second, a * 0.95f, cr)
        blob(canvas, cx + 0.40f * cr, cy - 0.10f * cr, outerR, main, a, cr)
        blob(canvas, cx + 0.70f * cr, cy - 0.50f * cr, outerR * 0.55f, accent, a * (0.55f + 0.35f * mid), cr)
        paint.xfermode = null

        // тонкий яркий ободок у края обложки
        val rim = ColorUtils.blendARGB(main, 0xFFFFFFFF.toInt(), 0.35f)
        val ra = (0.55f + 0.40f * bass) * gain
        paint.shader = RadialGradient(
            cx, cy, cr * 1.20f,
            intArrayOf(0x00000000, ColorUtils.setAlphaComponent(rim, (ra * 255).toInt()), ColorUtils.setAlphaComponent(rim, (ra * 0.35f * 255).toInt()), 0x00000000),
            floatArrayOf(0f, 0.95f / 1.20f, 1.06f / 1.20f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        if (attached && visibility == VISIBLE && windowVisibility == VISIBLE) postInvalidateDelayed(1000L / AppSettings.fps)
    }

    /** Мягкое цветное пятно: полная яркость до края обложки, затем плавное затухание. */
    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float, cover: Float) {
        val f = (cover / r).coerceIn(0.05f, 0.9f)
        val al = alpha.coerceIn(0f, 1f)
        paint.shader = RadialGradient(
            x, y, r,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, (al * 255).toInt()),
                ColorUtils.setAlphaComponent(color, (al * 255).toInt()),
                ColorUtils.setAlphaComponent(color, (al * 0.55f * 255).toInt()),
                ColorUtils.setAlphaComponent(color, (al * 0.18f * 255).toInt()),
                0x00000000
            ),
            floatArrayOf(0f, f, f + (1f - f) * 0.25f, f + (1f - f) * 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private companion object {
        val ADD = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        const val BG = 0xFF020206.toInt()
    }
}
