package com.wakewindow.app.data.mapper

import com.wakewindow.app.data.local.SavedTripEntity
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.trip.PlanningWaypoint
import com.wakewindow.app.domain.trip.SavedTrip
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.ZoneId

@Serializable
private data class WaypointDto(val id: String, val name: String, val latitude: Double, val longitude: Double)

/** JSON codec for [SavedTripEntity.waypointsJson] - see that field's own doc comment. */
private object WaypointListCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(waypoints: List<PlanningWaypoint>): String =
        json.encodeToString(ListSerializer(WaypointDto.serializer()), waypoints.map { WaypointDto(it.id, it.name, it.location.latitude, it.location.longitude) })

    fun decode(payloadJson: String): List<PlanningWaypoint> {
        if (payloadJson.isBlank()) return emptyList()
        return json.decodeFromString(ListSerializer(WaypointDto.serializer()), payloadJson)
            .map { PlanningWaypoint(name = it.name, location = GeoPoint(it.latitude, it.longitude), id = it.id) }
    }
}

object SavedTripMapper {

    fun toDomain(entity: SavedTripEntity): SavedTrip = SavedTrip(
        id = entity.id,
        name = entity.name,
        departure = PlanningWaypoint(entity.departureName, GeoPoint(entity.departureLatitude, entity.departureLongitude), id = entity.departureId),
        destination = PlanningWaypoint(entity.destinationName, GeoPoint(entity.destinationLatitude, entity.destinationLongitude), id = entity.destinationId),
        waypoints = WaypointListCodec.decode(entity.waypointsJson),
        vesselProfileId = entity.vesselProfileId,
        cruiseSpeedKts = entity.cruiseSpeedKts,
        notes = entity.notes,
        zoneId = runCatching { ZoneId.of(entity.zoneId) }.getOrDefault(ZoneId.systemDefault()),
        isFavorite = entity.isFavorite,
        savedAtEpochMillis = entity.savedAtEpochMillis,
        lastDepartureHourOfDay = entity.lastDepartureHourOfDay,
    )

    fun toEntity(trip: SavedTrip): SavedTripEntity = SavedTripEntity(
        id = trip.id,
        name = trip.name,
        departureName = trip.departure.name,
        departureLatitude = trip.departure.location.latitude,
        departureLongitude = trip.departure.location.longitude,
        departureId = trip.departure.id,
        destinationName = trip.destination.name,
        destinationLatitude = trip.destination.location.latitude,
        destinationLongitude = trip.destination.location.longitude,
        destinationId = trip.destination.id,
        waypointsJson = WaypointListCodec.encode(trip.waypoints),
        vesselProfileId = trip.vesselProfileId,
        cruiseSpeedKts = trip.cruiseSpeedKts,
        notes = trip.notes,
        zoneId = trip.zoneId.id,
        isFavorite = trip.isFavorite,
        savedAtEpochMillis = trip.savedAtEpochMillis,
        lastDepartureHourOfDay = trip.lastDepartureHourOfDay,
    )
}
