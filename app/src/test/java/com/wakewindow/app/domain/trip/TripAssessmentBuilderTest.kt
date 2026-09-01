package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.scoring.BoatingCategory
import com.wakewindow.app.domain.scoring.Hazard
import com.wakewindow.app.domain.scoring.HazardType
import com.wakewindow.app.domain.scoring.PointAssessment
import com.wakewindow.app.domain.vessel.VesselProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Pure combiner tests - no network, no fakes beyond hand-built [PointAssessment]s. See
 * docs/TRIP_ASSESSMENT.md and the sprint brief's Phase 9/25 requirements: one bad segment must
 * gate the whole trip, never get averaged away by calm ones on either side.
 */
class TripAssessmentBuilderTest {

    private val departureLoc = GeoPoint(28.408, -80.591)
    private val waypointLoc = GeoPoint(27.859, -80.448)
    private val destinationLoc = GeoPoint(27.470, -80.301)
    private val t0 = Instant.parse("2026-08-31T12:00:00Z")

    private fun point(
        location: GeoPoint,
        at: Instant,
        role: RouteSampleRole,
        category: BoatingCategory,
        hazards: List<Hazard> = emptyList(),
        confidence: Confidence = Confidence.high(),
    ) = PointAssessment(
        at = at,
        sample = RouteSample(location, role, 0.0, at),
        conditions = null,
        category = category,
        score = 100,
        hazards = hazards,
        confidence = confidence,
    )

    private fun tripPoint(kind: TripPointKind, name: String?, p: PointAssessment) =
        TripPointAssessment(kind = kind, name = name, point = p)

    private fun plan() = MarineTripPlan(
        departure = PlanningWaypoint("Port Canaveral", departureLoc),
        destination = PlanningWaypoint("Sebastian Inlet", destinationLoc),
        departureTime = t0,
        vessel = VesselProfile.default(),
        zoneId = ZoneId.of("UTC"),
        waypoints = listOf(PlanningWaypoint("Waypoint 1", waypointLoc)),
        cruiseSpeedKts = 20.0,
    )

    @Test
    fun `calm endpoints but a hazardous middle sample gates the overall trip category`() {
        val departure = tripPoint(TripPointKind.DEPARTURE, "Port Canaveral", point(departureLoc, t0, RouteSampleRole.DEPARTURE, BoatingCategory.GOOD))
        val hazard = Hazard(HazardType.THUNDERSTORM, "Thunderstorm probability 90%", t0.plusSeconds(3600), categoryCap = BoatingCategory.NO_GO)
        val weatherSample = tripPoint(
            TripPointKind.WEATHER_SAMPLE, null,
            point(waypointLoc, t0.plusSeconds(3600), RouteSampleRole.WEATHER_SAMPLE, BoatingCategory.NO_GO, hazards = listOf(hazard)),
        )
        val destination = tripPoint(TripPointKind.DESTINATION, "Sebastian Inlet", point(destinationLoc, t0.plusSeconds(7200), RouteSampleRole.DESTINATION, BoatingCategory.GOOD))

        val timeline = listOf(departure, weatherSample, destination)
        val leg = TripLegAssessment(
            leg = TripLeg(PlanningWaypoint("Port Canaveral", departureLoc), PlanningWaypoint("Sebastian Inlet", destinationLoc), 40.0, t0.plusSeconds(7200), false, true),
            from = departure, to = destination, weatherSamples = listOf(weatherSample),
        )
        val assessment = TripAssessmentBuilder.build(plan(), timeline, listOf(leg))

        assertEquals(BoatingCategory.NO_GO, assessment.overallCategory)
        assertTrue("mainConcern must name the actual hazard", assessment.mainConcern!!.contains("Thunderstorm"))
    }

    @Test
    fun `one hazardous waypoint is not averaged away by two calm points on either side`() {
        val departure = tripPoint(TripPointKind.DEPARTURE, "Port Canaveral", point(departureLoc, t0, RouteSampleRole.DEPARTURE, BoatingCategory.EXCELLENT))
        val hazard = Hazard(HazardType.WAVE_HEIGHT, "Seas around 6.0 ft", t0.plusSeconds(3600), categoryCap = BoatingCategory.POOR)
        val waypoint = tripPoint(TripPointKind.WAYPOINT, "Waypoint 1", point(waypointLoc, t0.plusSeconds(3600), RouteSampleRole.WAYPOINT, BoatingCategory.POOR, hazards = listOf(hazard)))
        val destination = tripPoint(TripPointKind.DESTINATION, "Sebastian Inlet", point(destinationLoc, t0.plusSeconds(7200), RouteSampleRole.DESTINATION, BoatingCategory.EXCELLENT))

        val timeline = listOf(departure, waypoint, destination)
        val assessment = TripAssessmentBuilder.build(plan(), timeline, emptyList())

        // A naive average of EXCELLENT/POOR/EXCELLENT would land back near GOOD - the whole
        // point of worst-case gating is that it must not.
        assertEquals(BoatingCategory.POOR, assessment.overallCategory)
    }

    @Test
    fun `an unavailable waypoint lowers confidence to at most its own level`() {
        val departure = tripPoint(TripPointKind.DEPARTURE, "Port Canaveral", point(departureLoc, t0, RouteSampleRole.DEPARTURE, BoatingCategory.GOOD, confidence = Confidence.high()))
        val unavailable = tripPoint(
            TripPointKind.WAYPOINT, "Waypoint 1",
            point(waypointLoc, t0.plusSeconds(3600), RouteSampleRole.WAYPOINT, BoatingCategory.UNAVAILABLE, confidence = Confidence.unavailable("Conditions unavailable near Waypoint 1")),
        )
        val destination = tripPoint(TripPointKind.DESTINATION, "Sebastian Inlet", point(destinationLoc, t0.plusSeconds(7200), RouteSampleRole.DESTINATION, BoatingCategory.GOOD, confidence = Confidence.high()))

        val assessment = TripAssessmentBuilder.build(plan(), listOf(departure, unavailable, destination), emptyList())

        assertEquals(ConfidenceLevel.UNAVAILABLE, assessment.confidence.level)
        assertTrue(assessment.confidence.reasons.any { it.contains("Waypoint 1") })
    }

    @Test
    fun `an entirely calm trip has a null main concern`() {
        val departure = tripPoint(TripPointKind.DEPARTURE, "Port Canaveral", point(departureLoc, t0, RouteSampleRole.DEPARTURE, BoatingCategory.EXCELLENT))
        val destination = tripPoint(TripPointKind.DESTINATION, "Sebastian Inlet", point(destinationLoc, t0.plusSeconds(3600), RouteSampleRole.DESTINATION, BoatingCategory.EXCELLENT))

        val assessment = TripAssessmentBuilder.build(plan(), listOf(departure, destination), emptyList())

        assertEquals(BoatingCategory.EXCELLENT, assessment.overallCategory)
        assertNull(assessment.mainConcern)
        assertTrue(assessment.worstHazards.isEmpty())
    }

    @Test
    fun `limit violations pass through untouched for the caller to surface`() {
        val departure = tripPoint(TripPointKind.DEPARTURE, "Port Canaveral", point(departureLoc, t0, RouteSampleRole.DEPARTURE, BoatingCategory.GOOD))
        val destination = tripPoint(TripPointKind.DESTINATION, "Sebastian Inlet", point(destinationLoc, t0.plusSeconds(3600), RouteSampleRole.DESTINATION, BoatingCategory.GOOD))
        val violation = TripPlanLimitViolation("A trip supports at most 10 planning waypoints (this plan has 12)")

        val assessment = TripAssessmentBuilder.build(plan(), listOf(departure, destination), emptyList(), limitViolations = listOf(violation))

        assertEquals(listOf(violation), assessment.limitViolations)
    }
}
