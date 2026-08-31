package com.wakewindow.app.domain.route

import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.vessel.VesselProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BoatingPlanTest {

    private fun launch() = MarinePlace(
        id = "1",
        discovery = MarinePlaceCandidate(
            name = "Port Canaveral",
            location = com.wakewindow.app.domain.model.GeoPoint(28.408, -80.591),
            address = null,
            guessedType = MarinePlaceType.PORT,
        ),
    )

    @Test
    fun `return time before departure time is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoatingPlan(
                launch = launch(),
                departureTime = Instant.parse("2026-08-30T16:00:00Z"),
                returnTime = Instant.parse("2026-08-30T12:00:00Z"),
                vessel = VesselProfile.default(),
                zoneId = ZoneId.of("America/New_York"),
            )
        }
    }

    @Test
    fun `default route samples always start at departure and end at return`() {
        val plan = BoatingPlan(
            launch = launch(),
            departureTime = Instant.parse("2026-08-30T12:00:00Z"),
            returnTime = Instant.parse("2026-08-30T20:00:00Z"),
            vessel = VesselProfile.default(),
            zoneId = ZoneId.of("America/New_York"),
        )
        val samples = plan.defaultRouteSamples()
        assertEquals(RouteSampleRole.DEPARTURE, samples.first().role)
        assertEquals(plan.departureTime, samples.first().estimatedTime)
        assertEquals(RouteSampleRole.RETURN, samples.last().role)
        assertEquals(plan.returnTime, samples.last().estimatedTime)
    }

    @Test
    fun `an 8-hour outing produces underway samples strictly between departure and return`() {
        val plan = BoatingPlan(
            launch = launch(),
            departureTime = Instant.parse("2026-08-30T12:00:00Z"),
            returnTime = Instant.parse("2026-08-30T20:00:00Z"),
            vessel = VesselProfile.default(),
            zoneId = ZoneId.of("America/New_York"),
        )
        val samples = plan.defaultRouteSamples()
        val underway = samples.filter { it.role == RouteSampleRole.UNDERWAY }
        org.junit.Assert.assertTrue(underway.isNotEmpty())
        underway.forEach {
            org.junit.Assert.assertTrue(it.estimatedTime.isAfter(plan.departureTime))
            org.junit.Assert.assertTrue(it.estimatedTime.isBefore(plan.returnTime))
        }
    }
}
