package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.route.RouteSampleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** See docs/TRIP_PLANNING.md "Intermediate route sampling" and the sprint brief's Phase 8/25
 * requirements: no sample for a short leg, one or more for a longer leg, deterministic
 * time-aware interpolation - never randomized, never continuous oversampling. */
class WeatherSampleGeneratorTest {

    private val start = GeoPoint(28.408, -80.591) // Port Canaveral
    private val end = GeoPoint(27.470, -80.301) // Fort Pierce Inlet, ~55 NM away
    private val legStart = Instant.parse("2026-08-31T12:00:00Z")

    private fun leg(distanceNm: Double, arrival: Instant) = TripLeg(
        from = PlanningWaypoint("From", start),
        to = PlanningWaypoint("To", end),
        planningDistanceNm = distanceNm,
        estimatedArrival = arrival,
        isManualArrival = false,
        isResolved = true,
    )

    @Test
    fun `a short leg gets no intermediate weather sample`() {
        val shortLeg = leg(distanceNm = 8.0, arrival = legStart.plusSeconds(1800))
        assertEquals(emptyList<Any>(), WeatherSampleGenerator.samplesFor(shortLeg, legStart))
    }

    @Test
    fun `a leg just past the threshold gets exactly one sample`() {
        val leg = leg(distanceNm = 20.0, arrival = legStart.plusSeconds(3600))
        val samples = WeatherSampleGenerator.samplesFor(leg, legStart)
        assertEquals(1, samples.size)
        assertEquals(RouteSampleRole.WEATHER_SAMPLE, samples.single().role)
    }

    @Test
    fun `a very long leg is capped at the documented maximum sample count`() {
        val veryLongLeg = leg(distanceNm = 400.0, arrival = legStart.plusSeconds(20 * 3600))
        val samples = WeatherSampleGenerator.samplesFor(veryLongLeg, legStart)
        assertEquals(TripPlanLimits.MAX_WEATHER_SAMPLES_PER_LEG, samples.size)
    }

    @Test
    fun `sample times are interpolated proportionally between leg start and arrival, not the departure hour reused`() {
        val arrival = legStart.plusSeconds(4 * 3600) // 4-hour leg
        val leg = leg(distanceNm = 60.0, arrival = arrival) // -> 2 samples
        val samples = WeatherSampleGenerator.samplesFor(leg, legStart)
        assertTrue(samples.size >= 2)
        samples.forEach { sample ->
            assertTrue("sample time must be strictly after leg start", sample.estimatedTime.isAfter(legStart))
            assertTrue("sample time must be strictly before arrival", sample.estimatedTime.isBefore(arrival))
        }
        // Times must be strictly increasing with fraction - a later sample is never timed earlier.
        val times = samples.map { it.estimatedTime }
        assertEquals(times, times.sorted())
    }

    @Test
    fun `sample locations are interpolated along the great-circle line, matching GeoPoint's own interpolation`() {
        val arrival = legStart.plusSeconds(3600)
        val leg = leg(distanceNm = 20.0, arrival = arrival)
        val sample = WeatherSampleGenerator.samplesFor(leg, legStart).single()
        val expectedFraction = sample.progressFraction
        val expectedLocation = start.interpolateTo(end, expectedFraction)
        assertEquals(expectedLocation.latitude, sample.location.latitude, 0.0001)
        assertEquals(expectedLocation.longitude, sample.location.longitude, 0.0001)
    }

    @Test
    fun `never generates a sample role that could be confused with a user-chosen waypoint`() {
        val leg = leg(distanceNm = 100.0, arrival = legStart.plusSeconds(5 * 3600))
        val samples = WeatherSampleGenerator.samplesFor(leg, legStart)
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.role == RouteSampleRole.WEATHER_SAMPLE })
    }
}
