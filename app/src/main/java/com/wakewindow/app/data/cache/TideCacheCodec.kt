package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.tide.TideEvent
import com.wakewindow.app.domain.tide.TideEventType
import com.wakewindow.app.domain.tide.TideStation
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/** Station metadata is pure geography (id/name/coordinates/datum) - see docs/CACHE_POLICY.md
 * "CO-OPS station metadata," why this gets the longest TTL in the whole cache expansion. */
@Serializable
private data class TideStationDto(
    val stationId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceNm: Double,
    val datum: String,
)

@Serializable
private data class TideEventDto(val type: String, val timeEpochMillis: Long, val heightFt: Double)

object TideCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeStation(station: TideStation): String = json.encodeToString(
        TideStationDto.serializer(),
        TideStationDto(station.stationId, station.name, station.location.latitude, station.location.longitude, station.distanceNm, station.datum),
    )

    fun decodeStation(payload: String): TideStation {
        val dto = json.decodeFromString(TideStationDto.serializer(), payload)
        return TideStation(dto.stationId, dto.name, GeoPoint(dto.latitude, dto.longitude), dto.distanceNm, dto.datum)
    }

    fun encodeEvents(events: List<TideEvent>): String =
        json.encodeToString(ListSerializer(TideEventDto.serializer()), events.map { TideEventDto(it.type.name, it.time.toEpochMilli(), it.heightFt) })

    fun decodeEvents(payload: String): List<TideEvent> =
        json.decodeFromString(ListSerializer(TideEventDto.serializer()), payload).map {
            TideEvent(runCatching { TideEventType.valueOf(it.type) }.getOrDefault(TideEventType.HIGH), Instant.ofEpochMilli(it.timeEpochMillis), it.heightFt)
        }
}
