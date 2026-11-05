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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Свечение из центра круглой обложки. Цвета задаёт настроение песни (или выбранная палитра) и
 * меняются только плавно при смене песни или её части. Ритм даёт дыхание: свечение расширяется на ударах
 * и мягко сжимается. Пульс берётся из анализатора звука; если его нет, идёт по темпу песни (BPM).
 * Без колец, частиц и случайных цветов.
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
    private var colorReady = false

    // сглаженные показатели 0..1
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var pulse = 0f
    private var beatPhase = 0f
    private var prevBass = 0f

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

    private fun tone(h: Float, s: Float, v: Float): Int {
        hsv[0] = (h % 360f + 360f) % 360f; hsv[1] = s.coerceIn(0f, 1f); hsv[2] = v.coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Цвет-цель: настроение песни, готовая палитра или своя. */
    private fun targetColor(): Int {
        val mode = AppSettings.paletteMode
        return when {
            mode == 0 -> target.color
            mode == 8 -> tone(AppSettings.customHue.toFloat(), 1f, 1f)
            else -> tone(MoodPalette.fixed[(mode - 1).coerceIn(0, MoodPalette.fixed.lastIndex)].second.toFloat(), 1f, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastMs = now
        BeatSource.tick(PlayerState.playing && MusicService.isRunning)
        val playing = PlayerState.playing && MusicService.isRunning

        // Смена цвета: только плавный переход к цели (скорость в настройках, по умолчанию около 1.5 с)
        val rate = 0.4f + 3.2f * (AppSettings.colorSpeed / 100f)
        val k = 1f - exp(-rate * dt)
        val tc = targetColor()
        if (!colorReady) { baseColor = tc; colorReady = true }
        baseColor = ColorUtils.blendARGB(baseColor, tc, k)
        brightness += (target.brightness - brightness) * k
        reactivity += (target.reactivity - reactivity) * k

        val sens = AppSettings.beatSensitivity / 100f * (0.8f + 0.5f * reactivity)
        fun boost(x: Float) = (sqrt(x.coerceIn(0f, 1f)) * sens).coerceIn(0f, 1f)
        val rawBass = boost(BeatSource.bass)
        mid = ema(mid, boost(BeatSource.mid), dt, 6f, 1.5f)
        treble = ema(treble, boost(BeatSource.treble), dt, 8f, 2f)

        // Фаза по темпу песни; удар по басу подтягивает её к нулю, чтобы пульс шёл в такт
        val bpm = target.bpm.coerceIn(0, 220)
        val live = BeatSource.isLive
        if (playing && bpm > 0 && AppSettings.tempoLock) {
            beatPhase = (beatPhase + dt * bpm / 60f) % 1f
            if (live && rawBass > 0.6f && prevBass <= 0.6f) {
                val err = if (beatPhase > 0.5f) 1f - beatPhase else -beatPhase
                beatPhase = ((beatPhase + err * 0.35f) % 1f + 1f) % 1f
            }
        }
        prevBass = rawBass
        // Мягкий «вдох» от фазы: резкий в начале удара, плавный спад
        val wave = (0.5f * (1f + cos(beatPhase * 2f * PI.toFloat()))).pow(2.2f)
        val locked = playing && bpm > 0 && AppSettings.tempoLock
        val raw = when {
            !playing -> 0f
            live && locked -> (0.6f * rawBass + 0.4f * wave).coerceIn(0f, 1f)
            live -> rawBass
            locked -> 0.15f + 0.65f * wave // нет сигнала звука: дышит по темпу
            else -> 0.2f
        }
        // сглаживание пульса: быстрый рост, мягкий спад; ползунок «Плавность» растягивает спад
        val smooth = AppSettings.pulseSmooth / 100f
        pulse = ema(pulse, raw, dt, 22f - 10f * smooth, 5.5f - 4.0f * smooth)
        bass = pulse

        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(BG)
        val cx = w / 2f
        val cy = if (centerYPx > 0f) centerYPx else h * 0.40f
        val gain = AppSettings.auraBrightness / 100f * intensity
        val cr = if (coverRadiusPx > 0f) coverRadiusPx else w * 0.25f

        // дыхание: радиус от базового размера до базового + размах, яркость тоже чуть дышит
        val size = AppSettings.glowSize / 100f
        val amp = AppSettings.pulseAmplitude / 100f
        val outerR = cr * (2.0f * size + 1.5f * amp * pulse)
        val a = (gain * (0.42f + 0.48f * pulse * amp.coerceAtMost(1.2f)) * (0.85f + 0.15f * brightness) + 0.05f * treble).coerceIn(0f, 1f)
        val f = (cr / outerR).coerceIn(0.05f, 0.9f)

        // оттенок задан настроением; соседние цвета расходятся от центра на фиксированные углы
        Color.colorToHSV(baseColor, hsv)
        val hue = hsv[0]
        val sat = (AppSettings.paletteSaturation / 100f * (0.92f + 0.08f * mid)).coerceIn(0f, 1f)
        val fan = 0.15f + 1.25f * (AppSettings.paletteSpread / 100f)
        val c0 = tone(hue, sat, 1f)
        val c1 = tone(hue + 22f * fan, sat, 1f)
        val c2 = tone(hue + 48f * fan, sat, 0.95f)
        val c3 = tone(hue + 75f * fan, sat, 0.9f)
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
            val rim = ColorUtils.blendARGB(c0, 0xFFFFFFFF.toInt(), 0.30f)
            val ra = (0.40f + 0.45f * pulse) * gain
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
    setAura(if (tg == null) MoodPalette.DEFAULT else MoodPalette.from(tg.moodScores.ifEmpty { mapOf(tg.mood to 100) }, tg.arousal, tg.bpm))
}
