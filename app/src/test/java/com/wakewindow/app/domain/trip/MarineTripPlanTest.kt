package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.vessel.VesselProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Mode B foundation - see docs/TRIP_PLANNING.md. These are domain-only, network-free tests;
 * no UI or per-waypoint weather-fetch integration ships this sprint (see the class docs on
 * [MarineTripPlan] and [TripLegEstimator] for the explicit scoping).
 */
class MarineTripPlanTest {

    private val departure = PlanningWaypoint("Port Canaveral", GeoPoint(28.408, -80.591))
    private val destination = PlanningWaypoint("Fort Pierce Inlet", GeoPoint(27.470, -80.301))
    private val departureTime = Instant.parse("2026-08-31T12:00:00Z")

    private fun plan(
        waypoints: List<PlanningWaypoint> = emptyList(),
        cruiseSpeedKts: Double? = null,
    ) = MarineTripPlan(
        departure = departure,
        destination = destination,
        departureTime = departureTime,
        vessel = VesselProfile.default(),
        zoneId = ZoneId.of("UTC"),
        waypoints = waypoints,
        cruiseSpeedKts = cruiseSpeedKts,
    )

    @Test
    fun `with no waypoints, orderedPoints is just departure and destination`() {
        assertEquals(listOf(departure, destination), plan().orderedPoints)
    }

    @Test
    fun `manual waypoints are kept in the order given, between departure and destination`() {
        val wp1 = PlanningWaypoint("Sebastian Inlet", GeoPoint(27.859, -80.448))
        val trip = plan(waypoints = listOf(wp1))
        assertEquals(listOf(departure, wp1, destination), trip.orderedPoints)
    }

    @Test
    fun `with a cruise speed, each leg's arrival is computed from geodesic distance over speed`() {
        val trip = plan(cruiseSpeedKts = 20.0)
        val legs = TripLegEstimator.estimateLegs(trip)
        val leg = legs.single()
        val expectedHours = departure.location.distanceNmTo(destination.location) / 20.0
        val expectedArrival = departureTime.plusSeconds((expectedHours * 3600).toLong())
        assertEquals(expectedArrival, leg.estimatedArrival)
        assertTrue(leg.isResolved)
        assertTrue(!leg.isManualArrival)
    }

    @Test
    fun `a manual arrival always wins over a computed one`() {
        val manualTime = Instant.parse("2026-08-31T15:00:00Z")
        val trip = plan(
            waypoints = emptyList(),
            cruiseSpeedKts = 20.0,
        ).let { it.copy(destination = it.destination.copy(manualArrival = manualTime)) }
        val leg = TripLegEstimator.estimateLegs(trip).single()
        assertEquals(manualTime, leg.estimatedArrival)
        assertTrue(leg.isManualArrival)
        assertTrue(leg.isResolved)
    }

    @Test
    fun `with neither a cruise speed nor a manual arrival, the leg is honestly unresolved rather than fabricated`() {
        val trip = plan(cruiseSpeedKts = null)
        val leg = TripLegEstimator.estimateLegs(trip).single()
        assertEquals(departureTime, leg.estimatedArrival)
        assertTrue(!leg.isResolved)
    }

    @Test
    fun `a zero or negative cruise speed never produces a nonsensical (infinite or backward) ETA`() {
        val trip = plan(cruiseSpeedKts = 0.0)
        val leg = TripLegEstimator.estimateLegs(trip).single()
        assertEquals(departureTime, leg.estimatedArrival)
        assertTrue(!leg.isResolved)
    }

    @Test
    fun `planning distance is the real geodesic distance between the two points, not a route distance`() {
        val trip = plan()
        val leg = TripLegEstimator.estimateLegs(trip).single()
        assertEquals(departure.location.distanceNmTo(destination.location), leg.planningDistanceNm, 0.001)
    }

    @Test
    fun `total planning distance across multiple legs sums each leg`() {
        val wp1 = PlanningWaypoint("Sebastian Inlet", GeoPoint(27.859, -80.448))
        val trip = plan(waypoints = listOf(wp1))
        val legs = TripLegEstimator.estimateLegs(trip)
        val total = TripLegEstimator.totalPlanningDistanceNm(trip)
        assertEquals(legs.sumOf { it.planningDistanceNm }, total, 0.001)
        assertEquals(2, legs.size)
    }

    @Test
    fun `routeSamples produces one sample per point with departure and destination roles`() {
        val trip = plan(cruiseSpeedKts = 20.0)
        val samples = TripLegEstimator.routeSamples(trip)
        assertEquals(2, samples.size)
        assertEquals(RouteSampleRole.DEPARTURE, samples.first().role)
        assertEquals(RouteSampleRole.DESTINATION, samples.last().role)
        assertEquals(0.0, samples.first().progressFraction, 0.001)
        assertEquals(1.0, samples.last().progressFraction, 0.001)
    }

    @Test
    fun `routeSamples marks intermediate manual waypoints distinctly from the destination`() {
        val wp1 = PlanningWaypoint("Sebastian Inlet", GeoPoint(27.859, -80.448))
        val trip = plan(waypoints = listOf(wp1), cruiseSpeedKts = 20.0)
        val samples = TripLegEstimator.routeSamples(trip)
        assertEquals(3, samples.size)
        assertEquals(RouteSampleRole.DEPARTURE, samples[0].role)
        assertEquals(RouteSampleRole.WAYPOINT, samples[1].role)
        assertEquals(RouteSampleRole.DESTINATION, samples[2].role)
    }

    @Test
    fun `routeSamples never crashes on a same-point departure and destination (zero total distance)`() {
        val samePoint = departure.copy(name = "Also Port Canaveral")
        val trip = MarineTripPlan(
            departure = departure,
            destination = samePoint,
            departureTime = departureTime,
            vessel = VesselProfile.default(),
            zoneId = ZoneId.of("UTC"),
            cruiseSpeedKts = 20.0,
        )
        val samples = TripLegEstimator.routeSamples(trip)
        assertEquals(2, samples.size)
    }
}
