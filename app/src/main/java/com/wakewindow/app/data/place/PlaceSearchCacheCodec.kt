package com.wakewindow.app.data.place

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class CachedCandidateDto(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val guessedType: String,
    val sourceType: String,
    val sourceId: String? = null,
)

/** Serializes a place-search result list for [com.wakewindow.app.data.cache.DurableCache] -
 * see docs/CACHE_POLICY.md "Place searches." An unrecognized enum value (e.g. an older cached
 * entry from before a type was added) falls back to [MarinePlaceType.OTHER]/
 * [PlaceSourceType.GEOCODING] rather than failing to load, matching this codebase's existing
 * Room-entity convention (see [com.wakewindow.app.data.mapper.SavedLaunchMapper]). */
object PlaceSearchCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(candidates: List<MarinePlaceCandidate>): String =
        json.encodeToString(
            ListSerializer(CachedCandidateDto.serializer()),
            candidates.map {
                CachedCandidateDto(
                    name = it.name,
                    latitude = it.location.latitude,
                    longitude = it.location.longitude,
                    address = it.address,
                    guessedType = it.guessedType.name,
                    sourceType = it.sourceType.name,
                    sourceId = it.sourceId,
                )
            },
        )

    fun decode(payload: String): List<MarinePlaceCandidate> =
        json.decodeFromString(ListSerializer(CachedCandidateDto.serializer()), payload).map { dto ->
            MarinePlaceCandidate(
                name = dto.name,
                location = GeoPoint(dto.latitude, dto.longitude),
                address = dto.address,
                guessedType = runCatching { MarinePlaceType.valueOf(dto.guessedType) }.getOrDefault(MarinePlaceType.OTHER),
                sourceType = runCatching { PlaceSourceType.valueOf(dto.sourceType) }.getOrDefault(PlaceSourceType.GEOCODING),
                sourceId = dto.sourceId,
            )
        }
}
