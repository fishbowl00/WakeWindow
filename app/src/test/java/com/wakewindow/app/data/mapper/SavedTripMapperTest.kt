package com.wakewindow.app.data.mapper

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.trip.PlanningWaypoint
import com.wakewindow.app.domain.trip.SavedTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Round-trip coverage for [SavedTripMapper] - the same testing tier this codebase already uses
 * for persistence (mapper-level round-trip, no real Room/Robolectric integration test exists
 * for any entity in this project) - see docs/TRIP_PLANNING.md "Saved trips." */
class SavedTripMapperTest {

    private fun trip(waypoints: List<PlanningWaypoint> = emptyList()) = SavedTrip(
        id = "trip-1",
        name = "Canaveral to Sebastian",
        departure = PlanningWaypoint("Port Canaveral", GeoPoint(28.408, -80.591)),
        destination = PlanningWaypoint("Sebastian Inlet", GeoPoint(27.859, -80.448)),
        waypoints = waypoints,
        vesselProfileId = "custom-1",
        cruiseSpeedKts = 22.0,
        notes = "Watch for chop near the inlet",
        zoneId = ZoneId.of("America/New_York"),
        isFavorite = true,
        savedAtEpochMillis = 1_700_000_000_000L,
        lastDepartureHourOfDay = 7,
    )

    @Test
    fun `a trip with no waypoints round-trips through entity encoding unchanged`() {
        val original = trip()
        val roundTripped = SavedTripMapper.toDomain(SavedTripMapper.toEntity(original))
        assertEquals(original, roundTripped)
    }

    @Test
    fun `a trip with multiple waypoints preserves order, names, coordinates, and ids`() {
        val wp1 = PlanningWaypoint("Waypoint A", GeoPoint(28.0, -80.5))
        val wp2 = PlanningWaypoint("Waypoint B", GeoPoint(27.9, -80.4))
        val original = trip(waypoints = listOf(wp1, wp2))

        val roundTripped = SavedTripMapper.toDomain(SavedTripMapper.toEntity(original))

        assertEquals(listOf(wp1, wp2), roundTripped.waypoints)
    }

    @Test
    fun `a manual arrival on a waypoint is deliberately dropped on save - it would be a stale timestamp on reuse`() {
        val wpWithManualArrival = PlanningWaypoint("Waypoint A", GeoPoint(28.0, -80.5), manualArrival = java.time.Instant.parse("2026-08-31T12:00:00Z"))
        val original = trip(waypoints = listOf(wpWithManualArrival))

        val roundTripped = SavedTripMapper.toDomain(SavedTripMapper.toEntity(original))

        assertTrue(roundTripped.waypoints.single().manualArrival == null)
    }

    @Test
    fun `an unresolvable stored zone id falls back to the system default rather than crashing`() {
        val entity = SavedTripMapper.toEntity(trip()).copy(zoneId = "Not/A/Real/Zone")
        val decoded = SavedTripMapper.toDomain(entity)
        assertEquals(ZoneId.systemDefault(), decoded.zoneId)
    }

    @Test
    fun `a blank waypoints payload decodes to an empty list rather than throwing`() {
        val entity = SavedTripMapper.toEntity(trip()).copy(waypointsJson = "")
        val decoded = SavedTripMapper.toDomain(entity)
        assertEquals(emptyList<PlanningWaypoint>(), decoded.waypoints)
    }
}
