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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Свечение из центра обложки: один центр, цвет расходится наружу и гаснет. Бас сильно меняет радиус
 * и яркость. Цвет живой: оттенок плывёт по всему кругу, и чем быстрее и громче музыка, тем быстрее
 * и в тёплую сторону; на медленных частях уходит в холодные тона. Настроение песни задаёт центр палитры.
 * Показатели сглажены (EMA), поэтому движение крупное, но плавное. Без колец, частиц и вращения.
 */
class AuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Центр свечения по вертикали в пикселях (за обложкой); пока не задан, берётся 40% высоты. */
    var centerYPx = -1f

    /** Радиус круглой обложки в пикселях: цветной ореол начинается от её края. */
    var coverRadiusPx = 0f

    /** Общая сила свечения: 1 для плеера, меньше для списка, чтобы не мешать читать. */
    var intensity = 1f

    /** Тонкий светлый ободок у края обложки (нужен только в плеере). */
    var showRim = true

    private var target = MoodPalette.DEFAULT
    private var baseColor = target.color
    private var brightness = target.brightness
    private var reactivity = target.reactivity
    private var speed = target.speed

    // сглаженные показатели 0..1
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var activity = 0.3f // медленная «энергия» последних секунд: темп и громкость
    private var drift = 0f // накопленный сдвиг оттенка в градусах

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
        return cur + (raw - cur) * (1f - exp(-rate * dt))
    }

    /** Среднее двух оттенков по кратчайшей дуге круга, t от 0 до 1. */
    private fun lerpHue(a: Float, b: Float, t: Float): Float {
        var d = ((b - a + 540f) % 360f) - 180f
        return (a + d * t + 360f) % 360f
    }

    private fun tone(h: Float, s: Float, v: Float): Int {
        hsv[0] = (h % 360f + 360f) % 360f; hsv[1] = s.coerceIn(0f, 1f); hsv[2] = v.coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastMs = now
        BeatSource.tick(PlayerState.playing && MusicService.isRunning)

        // плавный переход палитры настроения (около секунды)
        val k = 1f - exp(-2.5f * dt)
        baseColor = ColorUtils.blendARGB(baseColor, target.color, k)
        brightness += (target.brightness - brightness) * k
        reactivity += (target.reactivity - reactivity) * k
        speed += (target.speed - speed) * k

        // чувствительность: сжимаем динамику корнем, чтобы средний звук тоже был заметен
        val sens = AppSettings.beatSensitivity / 100f * (0.8f + 0.5f * reactivity)
        fun boost(x: Float) = (sqrt(x.coerceIn(0f, 1f)) * sens).coerceIn(0f, 1f)
        // бас: очень быстрый рост (около 0.04 с) и умеренное затухание (около 0.4 с)
        bass = ema(bass, boost(BeatSource.bass), dt, 30f, 2.5f)
        mid = ema(mid, boost(BeatSource.mid), dt, 8f, 2f)
        treble = ema(treble, boost(BeatSource.treble), dt, 10f, 3f)
        // энергия и темп последних секунд: реагирует за 1-2 секунды
        activity = ema(activity, (0.65f * boost(BeatSource.level) + 0.35f * bass).coerceIn(0f, 1f), dt, 0.9f, 0.5f)

        // оттенок: центр от настроения, смещение от темпа (быстро: к тёплым, медленно: к холодным),
        // плюс дрейф по кругу, скорость которого растёт с энергией и ударами
        Color.colorToHSV(baseColor, hsv)
        val moodHue = hsv[0]
        val tempoHue = lerpHue(235f, 8f, activity.pow(0.8f)) // тихо: синий, громко: красно-оранжевый
        drift += dt * (6f + 70f * activity + 40f * bass) * speed
        val wander = (35f + 90f * activity) * sin(drift * PI.toFloat() / 180f)
        val hue = lerpHue(moodHue, tempoHue, 0.45f) + wander + (mid - 0.5f) * 40f
        val sat = (0.78f + 0.22f * mid).coerceIn(0f, 1f)

        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(BG)
        val cx = w / 2f
        val cy = if (centerYPx > 0f) centerYPx else h * 0.40f
        val gain = AppSettings.auraBrightness / 100f * intensity
        val cr = if (coverRadiusPx > 0f) coverRadiusPx else w * 0.25f

        // большая амплитуда: радиус от 1.9 до 4.4 радиусов обложки, яркость от 35% до 100%
        val outerR = cr * (1.9f + 2.5f * bass)
        val a = (gain * (0.30f + 0.70f * bass) * (0.8f + 0.2f * brightness) + 0.08f * treble).coerceIn(0f, 1f)
        val f = (cr / outerR).coerceIn(0.05f, 0.9f)
        // от центра наружу оттенок уходит дальше по кругу: вид «расходящихся» цветов
        val c0 = tone(hue, sat, 1f)
        val c1 = tone(hue + 28f, sat, 1f)
        val c2 = tone(hue + 65f, sat, 0.95f)
        val c3 = tone(hue + 110f, sat, 0.9f)
        fun al(x: Float) = (a * x * 255f).toInt().coerceIn(0, 255)
        paint.shader = RadialGradient(
            cx, cy, outerR,
            intArrayOf(
                ColorUtils.setAlphaComponent(c0, al(1f)),
                ColorUtils.setAlphaComponent(c0, al(1f)),
                ColorUtils.setAlphaComponent(c1, al(0.75f)),
                ColorUtils.setAlphaComponent(c2, al(0.38f)),
                ColorUtils.setAlphaComponent(c3, al(0.12f)),
                0x00000000
            ),
            floatArrayOf(0f, f, f + (1f - f) * 0.22f, f + (1f - f) * 0.5f, f + (1f - f) * 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        if (showRim) {
        // тонкий светлый ободок у края обложки
        val rim = ColorUtils.blendARGB(c0, 0xFFFFFFFF.toInt(), 0.35f)
        val ra = (0.45f + 0.50f * bass) * gain
        paint.shader = RadialGradient(
            cx, cy, cr * 1.20f,
            intArrayOf(0x00000000, ColorUtils.setAlphaComponent(rim, (ra * 255).toInt().coerceIn(0, 255)),
                ColorUtils.setAlphaComponent(rim, (ra * 0.3f * 255).toInt().coerceIn(0, 255)), 0x00000000),
            floatArrayOf(0f, 0.95f / 1.20f, 1.06f / 1.20f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        if (attached && visibility == VISIBLE && windowVisibility == VISIBLE) postInvalidateDelayed(1000L / AppSettings.fps)
    }

    private companion object {
        const val BG = 0xFF020206.toInt()
    }
}

/** Окрашивает свечение по настроению песни (или нейтрально, если песня не размечена). */
fun AuraView.applyTrack(context: Context, track: MusicTrack?) {
    val tg = track?.let { MusicTags.get(context, it) }?.takeIf { MusicTags.isTagged(it) }
    setAura(if (tg == null) MoodPalette.DEFAULT else MoodPalette.from(tg.moodScores.ifEmpty { mapOf(tg.mood to 100) }, tg.arousal))
}
