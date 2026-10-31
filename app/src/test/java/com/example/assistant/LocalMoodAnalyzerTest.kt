package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LocalMoodAnalyzerTest {
    @Test fun moodScoresAlwaysTotalOneHundred() {
        val cases = listOf(
            AudioFeatures(0.02, 900.0, 0.55, 0.03, 0.08, 0.01, 0.05, 72, 1.2, 22050),
            AudioFeatures(0.24, 4200.0, 0.18, 0.22, 0.38, 0.7, 0.65, 128, 3.4, 22050),
            AudioFeatures(0.08, 1800.0, 0.35, 0.08, 0.16, 0.12, 0.32, 105, 1.8, 22050)
        )
        for (features in cases) {
            val result = LocalMoodAnalyzer.classify(features)
            assertEquals(100, result.scores.values.sum())
            assertTrue(result.scores.values.all { it in 0..100 })
            assertTrue(result.energy in 1..5)
            assertTrue(result.confidence in 40..88)
        }
    }

    @Test fun extractsFeaturesFromPcmSignal() {
        val rate = 22050
        val samples = FloatArray(rate * 2) { i -> (0.2 * sin(2.0 * PI * 440.0 * i / rate)).toFloat() }
        val features = LocalMoodAnalyzer.extractFeatures(samples, rate)
        assertTrue(features.rms > 0.1)
        assertTrue(features.centroid > 100.0)
        assertTrue(features.count > 0)
    }
}
