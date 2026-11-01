package com.example.assistant

import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Ритм играющей музыки для анимации фона: [bass] (низкие частоты, «удар») и [level] (общая громкость), оба от 0 до 1.
 * Настоящий звук берётся через [Visualizer] (нужно разрешение на запись звука). Если его нет,
 * пока музыка играет, фон живёт по мягкому условному ритму, чтобы экран не был мёртвым.
 */
object BeatSource {
    @Volatile var bass = 0f
        private set
    @Volatile var level = 0f
        private set

    private var visualizer: Visualizer? = null
    private var live = false
    private var lastRealFrame = 0L

    fun attach(sessionId: Int) {
        detach()
        if (sessionId == 0) return // 0 это общий микс всего телефона, его не трогаем
        try {
            val v = Visualizer(sessionId)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) process(fft, samplingRate)
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            visualizer = v
            live = true
        } catch (e: Throwable) {
            // нет разрешения или эффект не поддерживается: останется условный ритм
            visualizer = null
            live = false
        }
    }

    fun detach() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Throwable) {
            // игнорируем: объект и так выбрасывается
        }
        visualizer = null
        live = false
        bass = 0f
        level = 0f
    }

    private fun process(fft: ByteArray, samplingRateMilliHz: Int) {
        val bins = fft.size / 2
        if (bins < 4) return
        val hz = samplingRateMilliHz / 1000f
        val binHz = hz / 2f / bins
        if (binHz <= 0f) return
        var bassSum = 0f
        var bassN = 0
        var allSum = 0f
        var allN = 0
        for (k in 1 until bins) {
            val f = k * binHz
            if (f > 4000f) break
            val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            if (f in 40f..220f) { bassSum += m; bassN++ }
            allSum += m; allN++
        }
        val b = if (bassN > 0) (bassSum / bassN / 70f).coerceIn(0f, 1f) else 0f
        val l = if (allN > 0) (allSum / allN / 28f).coerceIn(0f, 1f) else 0f
        // быстро растём на ударе, плавно затухаем
        bass = if (b > bass) bass + (b - bass) * 0.7f else bass * 0.9f + b * 0.1f
        level = if (l > level) level + (l - level) * 0.5f else level * 0.93f + l * 0.07f
        lastRealFrame = SystemClock.uptimeMillis()
    }

    /** Вызывается из рисующего вида на каждом кадре. Без настоящего звука делает условный ритм. */
    fun tick(playing: Boolean) {
        if (!playing) {
            bass *= 0.92f
            level *= 0.95f
            return
        }
        val now = SystemClock.uptimeMillis()
        val realFresh = live && now - lastRealFrame < 400
        if (realFresh) return
        val t = now / 1000.0
        val pulse = (sin(t * 2 * PI * 2.0) * 0.5 + 0.5).toFloat()
        val slow = (sin(t * 2 * PI * 0.37) * 0.5 + 0.5).toFloat()
        val fakeBass = 0.15f + 0.55f * pulse * pulse * (0.6f + 0.4f * slow)
        val fakeLevel = 0.25f + 0.3f * slow + 0.15f * pulse
        bass += (fakeBass - bass) * 0.25f
        level += (fakeLevel - level) * 0.15f
    }
}
