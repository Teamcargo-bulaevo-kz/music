package com.teamcargo.musicintelligence.models

/**
 * Результат локального DSP-анализа. Значения являются признаками алгоритма,
 * а не медицинскими/субъективными оценками и должны сопровождаться confidence.
 */
data class TrackAnalysis(
    val trackId: Long,
    val bpm: Float? = null,
    val bpmConfidence: Float? = null,
    val beatRegularity: Float? = null,
    val energy: Float? = null,
    val brightness: Float? = null,
    val danceability: Float? = null,
    val genre: List<String> = emptyList(),
    val mood: List<String> = emptyList(),
    val analysisVersion: Int,
    val analyzedAt: Long
)
