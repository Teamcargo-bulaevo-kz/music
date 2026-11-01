package com.teamcargo.musicintelligence.metadata

import com.teamcargo.musicintelligence.models.MusicMetadata

/** Сменный источник каталожных метаданных: MusicBrainz, партнёрский API и т.п. */
interface MetadataProvider {
    val providerId: String

    suspend fun search(query: MetadataQuery): List<MetadataCandidate>
}

data class MetadataQuery(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null
)

data class MetadataCandidate(
    val metadata: MusicMetadata,
    /** Уверенность именно в совпадении записи, а не в качестве каждого поля. */
    val matchConfidence: Float,
    val providerId: String
) {
    init {
        require(matchConfidence in 0f..1f)
        require(providerId.isNotBlank())
    }
}
