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
        assertEquals(2.0, features.durationSeconds, 0.1)
    }

    @Test fun splitsLongTrackIntoOverlappingMoodSegments() {
        val rate = 11025
        val samples = FloatArray(rate * 45) { i ->
            val amplitude = if (i < rate * 20) 0.04 else 0.30
            (amplitude * sin(2.0 * PI * 220.0 * i / rate)).toFloat()
        }
        val segments = LocalMoodAnalyzer.analyzeSegments(samples, rate)
        assertEquals(4, segments.size)
        assertEquals(0, segments.first().startSeconds)
        assertEquals(45, segments.last().endSeconds)
        assertTrue(segments.all { it.endSeconds > it.startSeconds })
        assertTrue(segments.all { it.scores.values.sum() == 100 })
        assertTrue(segments.all { it.valence in 0..100 && it.arousal in 0..100 && it.tension in 0..100 })
    }
    @Test fun spectralOnsetsProduceTempoEstimateForRegularPulses() {
        val rate = 22050
        val samples = FloatArray(rate * 8) { i ->
            val time = i.toDouble() / rate
            val phase = time % 0.5
            val pulse = if (phase < 0.075) 0.32 else 0.008
            (pulse * sin(2.0 * PI * 880.0 * time)).toFloat()
        }
        val features = LocalMoodAnalyzer.extractFeatures(samples, rate)
        assertTrue("Regular spectral attacks should produce a BPM estimate", features.bpm > 0)
    }

    @Test fun detectsTrackLevelDynamicsAcrossLongerAudio() {
        val rate = 22050
        val samples = FloatArray(rate * 8) { i ->
            val amplitude = if (i < rate * 4) 0.04 else 0.35
            (amplitude * sin(2.0 * PI * 440.0 * i / rate)).toFloat()
        }
        val features = LocalMoodAnalyzer.extractFeatures(samples, rate)
        assertEquals(8.0, features.durationSeconds, 0.1)
        assertTrue("Expected a change in dynamics between sections", features.variation > 0.2)
        val result = LocalMoodAnalyzer.classify(features)
        assertTrue(result.valence in 0..100)
        assertTrue(result.arousal in 0..100)
        assertTrue(result.tension in 0..100)
        assertTrue(result.danceability in 0..100)
    }
}
