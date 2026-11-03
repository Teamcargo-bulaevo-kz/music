package com.example.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Фон плеера: свечение от центра (за обложкой), затухающее к краям. Цвет и характер мерцания
 * задаёт [Aura] (настроение песни), пульс берётся из [BeatSource]. Цвета меняются плавно.
 */
class AuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Где центр свечения по высоте (0..1) — примерно за обложкой. */
    var centerY = 0.40f

    private var target = MoodPalette.DEFAULT
    private var color = target.color
    private var brightness = target.brightness
    private var reactivity = target.reactivity
    private var speed = target.speed
    private var pulse = 0f
    private var phase = 0f
    private var lastMs = 0L
    private var attached = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = 0xFF05060F.toInt()

    fun setAura(aura: Aura) { target = aura }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; lastMs = 0L; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { attached = false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { lastMs = 0L; postInvalidateOnAnimation() }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.033f else ((now - lastMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastMs = now
        BeatSource.tick(PlayerState.playing && MusicService.isRunning)

        // плавный переход к цели (около секунды)
        val k = min(1f, dt * 2.2f)
        color = ColorUtils.blendARGB(color, target.color, k)
        brightness += (target.brightness - brightness) * k
        reactivity += (target.reactivity - reactivity) * k
        speed += (target.speed - speed) * k
        phase += dt * speed

        val beat = BeatSource.bass * reactivity
        val level = BeatSource.level * reactivity
        pulse = if (beat > pulse) pulse + (beat - pulse) * 0.7f else pulse * 0.90f + beat * 0.10f

        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(bg)
        val cx = w / 2f + sin(phase * 0.7f) * w * 0.03f
        val cy = h * centerY + sin(phase * 0.5f + 1.3f) * h * 0.015f
        val base = max(w, h * 0.6f)
        // внешнее мягкое свечение: затухает к краям
        val outerR = base * (0.62f + 0.10f * sin(phase) + 0.22f * pulse)
        val outerA = (0.16f + 0.40f * brightness * (0.55f + 0.45f * level) + 0.18f * pulse).coerceIn(0f, 0.85f)
        paint.shader = RadialGradient(
            cx, cy, outerR,
            intArrayOf(ColorUtils.setAlphaComponent(color, (outerA * 255).toInt()),
                ColorUtils.setAlphaComponent(color, (outerA * 90).toInt()), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // ядро: ярче и светлее, бьётся в такт
        val coreR = base * (0.24f + 0.16f * pulse)
        val core = ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.25f + 0.25f * pulse)
        val coreA = (0.20f + 0.45f * brightness * (0.4f + 0.6f * pulse)).coerceIn(0f, 0.8f)
        paint.shader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(ColorUtils.setAlphaComponent(core, (coreA * 255).toInt()), 0x00000000),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        if (attached && visibility == VISIBLE && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }
}
