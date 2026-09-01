package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.tide.CurrentEvent
import com.wakewindow.app.domain.tide.CurrentEventType
import com.wakewindow.app.domain.tide.CurrentStation
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
private data class CurrentStationDto(val stationId: String, val name: String, val latitude: Double, val longitude: Double, val distanceNm: Double)

@Serializable
private data class CurrentEventDto(val type: String, val timeEpochMillis: Long, val speedKts: Double, val directionDeg: Double? = null)

object CurrentCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeStation(station: CurrentStation): String = json.encodeToString(
        CurrentStationDto.serializer(),
        CurrentStationDto(station.stationId, station.name, station.location.latitude, station.location.longitude, station.distanceNm),
    )

    fun decodeStation(payload: String): CurrentStation {
        val dto = json.decodeFromString(CurrentStationDto.serializer(), payload)
        return CurrentStation(dto.stationId, dto.name, GeoPoint(dto.latitude, dto.longitude), dto.distanceNm)
    }

    fun encodeEvents(events: List<CurrentEvent>): String =
        json.encodeToString(
            ListSerializer(CurrentEventDto.serializer()),
            events.map { CurrentEventDto(it.type.name, it.time.toEpochMilli(), it.speedKts, it.directionDeg) },
        )

    fun decodeEvents(payload: String): List<CurrentEvent> =
        json.decodeFromString(ListSerializer(CurrentEventDto.serializer()), payload).map {
            CurrentEvent(runCatching { CurrentEventType.valueOf(it.type) }.getOrDefault(CurrentEventType.SLACK), Instant.ofEpochMilli(it.timeEpochMillis), it.speedKts, it.directionDeg)
        }
}
