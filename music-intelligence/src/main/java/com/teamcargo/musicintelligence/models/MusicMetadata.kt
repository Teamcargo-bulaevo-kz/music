package com.teamcargo.musicintelligence.models

/**
 * Источник и уверенность хранятся для каждого атрибута отдельно:
 * например, название может прийти из MediaStore, а ISRC — из MusicBrainz.
 */
data class SourcedValue<T>(
    val value: T,
    val source: String,
    val confidence: Float,
    val updatedAt: Long
) {
    init {
        require(source.isNotBlank())
        require(confidence in 0f..1f)
    }
}

data class MusicMetadata(
    val title: SourcedValue<String>? = null,
    val artist: SourcedValue<String>? = null,
    val album: SourcedValue<String>? = null,
    val durationMs: SourcedValue<Long>? = null,
    val isrc: SourcedValue<String>? = null,
    val musicBrainzRecordingId: SourcedValue<String>? = null,
    val genres: SourcedValue<List<String>>? = null
)
