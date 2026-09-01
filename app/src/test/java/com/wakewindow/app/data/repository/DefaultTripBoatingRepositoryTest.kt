package com.wakewindow.app.data.repository

import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.observation.MarineObservationOutcome
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.observation.ObservationFreshness
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.scoring.BoatingCategory
import com.wakewindow.app.domain.scoring.severityRank
import com.wakewindow.app.domain.tide.CurrentEventsOutcome
import com.wakewindow.app.domain.tide.CurrentProvider
import com.wakewindow.app.domain.tide.CurrentStationOutcome
import com.wakewindow.app.domain.tide.TideEventsOutcome
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStationOutcome
import com.wakewindow.app.domain.trip.MarineTripPlan
import com.wakewindow.app.domain.trip.PlanningWaypoint
import com.wakewindow.app.domain.trip.TripPlanLimits
import com.wakewindow.app.domain.trip.TripPointKind
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Integration-level tests through the real [DefaultTripBoatingRepository], hand-written fakes
 * only - matching [DefaultBoatingRepositoryTest]'s own philosophy. These specifically exercise
 * what's unique to Mode B: a distinct location/time per trip point, one hazardous point gating
 * the whole trip, per-point provider-failure resilience, and time-aware observation relevance -
 * see docs/TRIP_ASSESSMENT.md and the sprint brief's Phase 25 checklist.
 */
private fun calmConditions(location: GeoPoint, hour: Instant) = MarineConditions(
    timestamp = hour, location = location,
    sustainedWindKts = 8.0, gustKts = 10.0, waveHeightFt = 1.0, thunderstormProbabilityPercent = 5,
    source = SourceReference("Fake", null, hour), confidence = Confidence.high(),
)

private fun hourly(location: GeoPoint, start: Instant, end: Instant, conditionsForHour: (Instant) -> MarineConditions) =
    generateSequence(start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
        .takeWhile { !it.isAfter(end) }
        .map { conditionsForHour(it) }
        .toList()

class DefaultTripBoatingRepositoryTest {

    private val departureLoc = GeoPoint(28.408, -80.591) // Port Canaveral
    private val waypointLoc = GeoPoint(27.859, -80.448) // Sebastian Inlet
    private val destinationLoc = GeoPoint(27.470, -80.301) // Fort Pierce Inlet
    private val t0 = Instant.parse("2026-08-31T12:00:00Z")

    private fun plan(
        waypoints: List<PlanningWaypoint> = emptyList(),
        destination: PlanningWaypoint = PlanningWaypoint("Fort Pierce Inlet", destinationLoc),
        cruiseSpeedKts: Double? = 20.0,
    ) = MarineTripPlan(
        departure = PlanningWaypoint("Port Canaveral", departureLoc),
        destination = destination,
        departureTime = t0,
        vessel = VesselProfile.default(),
        zoneId = ZoneId.of("UTC"),
        waypoints = waypoints,
        cruiseSpeedKts = cruiseSpeedKts,
    )

    /** A calm series everywhere except within [stormRadiusNm] of [stormLocation], where
     * thunderstorm probability gates NO_GO (>= the default vessel's 40% tolerance, well past its
     * 90% hard-NO_GO threshold). Lets a single test target exactly one trip point. */
    private class LocationAwareGeneralProvider(
        private val stormLocation: GeoPoint,
        private val stormRadiusNm: Double = 5.0,
    ) : GeneralWeatherProvider {
        override val providerName = "FakeGeneral"
        override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
            val stormy = location.distanceNmTo(stormLocation) <= stormRadiusNm
            return ForecastOutcome.Success(
                generateSequence(start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
                    .takeWhile { !it.isAfter(end) }
                    .map { hour ->
                        MarineConditions(
                            timestamp = hour, location = location,
                            sustainedWindKts = 8.0, gustKts = 10.0, waveHeightFt = 1.0,
                            thunderstormProbabilityPercent = if (stormy) 95 else 5,
                            source = SourceReference("FakeGeneral", null, hour), confidence = Confidence.high(),
                        )
                    }.toList(),
            )
        }
    }

    private class CalmMarineProvider : MarineForecastProvider {
        override val providerName = "FakeMarine"
        override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
            ForecastOutcome.Success(
                generateSequence(start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
                    .takeWhile { !it.isAfter(end) }
                    .map { hour ->
                        MarineConditions(
                            timestamp = hour, location = location, waveHeightFt = 1.0,
                            source = SourceReference("FakeMarine", null, hour), confidence = Confidence.high(),
                        )
                    }.toList(),
            )
    }

    /** Throws for every request within [failRadiusNm] of [failLocation] - simulates exactly one
     * trip point's provider being unreachable while every other point succeeds normally. */
    private class FailingNearGeneralProvider(private val failLocation: GeoPoint, private val failRadiusNm: Double = 5.0) : GeneralWeatherProvider {
        override val providerName = "FailingNearGeneral"
        override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
            if (location.distanceNmTo(failLocation) <= failRadiusNm) throw RuntimeException("provider unreachable")
            return ForecastOutcome.Success(hourly(location, start, end) { calmConditions(location, it) })
        }
    }

    private class FailingNearMarineProvider(private val failLocation: GeoPoint, private val failRadiusNm: Double = 5.0) : MarineForecastProvider {
        override val providerName = "FailingNearMarine"
        override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
            if (location.distanceNmTo(failLocation) <= failRadiusNm) throw RuntimeException("provider unreachable")
            return ForecastOutcome.Success(hourly(location, start, end) { calmConditions(location, it) })
        }
    }

    private class FakeAlertProvider(private val outcome: MarineAlertOutcome = MarineAlertOutcome.Success(emptyList())) : MarineAlertProvider {
        override suspend fun activeAlerts(location: GeoPoint) = outcome
    }

    private class FakeTideProvider : TideProvider {
        override suspend fun nearestStation(location: GeoPoint) = TideStationOutcome.NotTidal
        override suspend fun events(stationId: String, date: LocalDate) = TideEventsOutcome.Success(emptyList())
    }

    private class FakeCurrentProvider : CurrentProvider {
        override suspend fun nearestStation(location: GeoPoint) = CurrentStationOutcome.NoStationNearby
        override suspend fun events(stationId: String, date: LocalDate) = CurrentEventsOutcome.Success(emptyList())
    }

    private class FakeObservationProvider(private val outcome: MarineObservationOutcome) : MarineObservationProvider {
        override val providerName = "FakeNDBC"
        override suspend fun nearestObservation(location: GeoPoint) = outcome
    }

    @Test
    fun `every trip point is evaluated at its own expected arrival time and location, not one shared timeline`() = runBlocking {
        // Storm only near the waypoint - departure and destination must stay calm.
        val repo = DefaultTripBoatingRepository(
            generalProviders = listOf(LocationAwareGeneralProvider(stormLocation = waypointLoc)),
            marineForecastProviders = listOf(CalmMarineProvider()),
            alertProvider = FakeAlertProvider(),
            tideProvider = FakeTideProvider(),
            currentProvider = FakeCurrentProvider(),
            clock = { t0 },
        )
        val assessment = repo.buildTripAssessment(plan(waypoints = listOf(PlanningWaypoint("Sebastian Inlet", waypointLoc))))

        val departure = assessment.timeline.first { it.kind == TripPointKind.DEPARTURE }
        val waypoint = assessment.timeline.first { it.kind == TripPointKind.WAYPOINT }
        val destination = assessment.timeline.first { it.kind == TripPointKind.DESTINATION }

        assertTrue("departure must stay calm", departure.category.severityRank <= BoatingCategory.GOOD.severityRank)
        assertEquals(BoatingCategory.NO_GO, waypoint.category)
        assertTrue("destination must stay calm", destination.category.severityRank <= BoatingCategory.GOOD.severityRank)
        assertEquals(BoatingCategory.NO_GO, assessment.overallCategory)
    }

    @Test
    fun `a provider failure at one waypoint reports that point unavailable without blocking the rest of the trip`() = runBlocking {
        val repo = DefaultTripBoatingRepository(
            generalProviders = listOf(FailingNearGeneralProvider(failLocation = waypointLoc)),
            marineForecastProviders = listOf(FailingNearMarineProvider(failLocation = waypointLoc)),
            alertProvider = FakeAlertProvider(),
            tideProvider = FakeTideProvider(),
            currentProvider = FakeCurrentProvider(),
            clock = { t0 },
        )
        val assessment = repo.buildTripAssessment(plan(waypoints = listOf(PlanningWaypoint("Sebastian Inlet", waypointLoc))))

        val waypoint = assessment.timeline.first { it.kind == TripPointKind.WAYPOINT }
        val departure = assessment.timeline.first { it.kind == TripPointKind.DEPARTURE }
        val destination = assessment.timeline.first { it.kind == TripPointKind.DESTINATION }

        assertEquals(BoatingCategory.UNAVAILABLE, waypoint.category)
        assertTrue("departure must still be assessed normally", departure.category != BoatingCategory.UNAVAILABLE)
        assertTrue("destination must still be assessed normally", destination.category != BoatingCategory.UNAVAILABLE)
        assertEquals(ConfidenceLevel.UNAVAILABLE, assessment.confidence.level)
    }

    @Test
    fun `a point far beyond the forecast horizon is reported unavailable, not fabricated, and sets a horizon warning`() = runBlocking {
        val farFuture = PlanningWaypoint("Distant Port", GeoPoint(25.0, -80.0))
        val trip = plan(destination = farFuture, cruiseSpeedKts = 1.0).let {
            // Force an arrival far beyond MAX_FORECAST_HORIZON via a manual arrival rather than
            // relying on an unrealistically slow cruise speed for the whole distance.
            it.copy(destination = it.destination.copy(manualArrival = t0.plus(TripPlanLimits.MAX_FORECAST_HORIZON.plusDays(2))))
        }
        val repo = DefaultTripBoatingRepository(
            generalProviders = listOf(CalmMarineProviderAsGeneral()),
            marineForecastProviders = listOf(CalmMarineProvider()),
            alertProvider = FakeAlertProvider(),
            tideProvider = FakeTideProvider(),
            currentProvider = FakeCurrentProvider(),
            clock = { t0 },
        )
        val assessment = repo.buildTripAssessment(trip)

        val destination = assessment.timeline.last()
        assertEquals(BoatingCategory.UNAVAILABLE, destination.category)
        assertNotNull(assessment.horizonWarning)
    }

    private class CalmMarineProviderAsGeneral : GeneralWeatherProvider {
        override val providerName = "FakeGeneral"
        override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
            ForecastOutcome.Success(
                generateSequence(start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
                    .takeWhile { !it.isAfter(end) }
                    .map { hour ->
                        MarineConditions(
                            timestamp = hour, location = location, sustainedWindKts = 8.0, waveHeightFt = 1.0, thunderstormProbabilityPercent = 5,
                            source = SourceReference("FakeGeneral", null, hour), confidence = Confidence.high(),
                        )
                    }.toList(),
            )
    }

    @Test
    fun `observation is only fetched for near-term points, never carried across a multi-day trip`() = runBlocking {
        val station = SelectedMarineStation(
            stationId = "41113", name = "Cape Canaveral Nearshore", location = departureLoc, distanceNm = 2.0,
            observedAt = t0.minusSeconds(600), ageMinutes = 10, freshness = ObservationFreshness.FRESH,
            hasWindData = true, hasWaveData = true, selectionReason = "test",
        )
        val observedConditions = calmConditions(departureLoc, t0.minusSeconds(600)).copy(observationAgeMinutes = 10)
        val farDestination = PlanningWaypoint(
            "Distant Port", GeoPoint(25.0, -80.0),
            manualArrival = t0.plus(java.time.Duration.ofDays(4)),
        )
        val trip = plan(destination = farDestination, cruiseSpeedKts = 20.0)

        val repo = DefaultTripBoatingRepository(
            generalProviders = listOf(CalmMarineProviderAsGeneral()),
            marineForecastProviders = listOf(CalmMarineProvider()),
            alertProvider = FakeAlertProvider(),
            tideProvider = FakeTideProvider(),
            currentProvider = FakeCurrentProvider(),
            observationProvider = FakeObservationProvider(MarineObservationOutcome.Success(station, observedConditions)),
            clock = { t0 },
        )
        val assessment = repo.buildTripAssessment(trip)

        val departure = assessment.timeline.first { it.kind == TripPointKind.DEPARTURE }
        val destination = assessment.timeline.last()

        assertTrue("departure is near 'now' so observation should apply", departure.observationApplicable)
        assertTrue("a multi-day-future point must not inherit a 'now' observation", !destination.observationApplicable)
        assertNull(destination.nearestObservationStation)
    }

    @Test
    fun `every provider throwing simultaneously for every point still returns a result rather than crashing`() = runBlocking {
        val repo = DefaultTripBoatingRepository(
            generalProviders = listOf(ThrowingGeneralProvider()),
            marineForecastProviders = listOf(ThrowingMarineProvider()),
            alertProvider = ThrowingAlertProvider(),
            tideProvider = ThrowingTideProvider(),
            observationProvider = ThrowingObservationProvider(),
            currentProvider = ThrowingCurrentProvider(),
            clock = { t0 },
        )
        val assessment = repo.buildTripAssessment(plan())
        assertEquals(BoatingCategory.UNAVAILABLE, assessment.overallCategory)
    }

    private class ThrowingGeneralProvider : GeneralWeatherProvider {
        override val providerName = "ThrowingGeneral"
        override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome = throw RuntimeException("down")
    }

    private class ThrowingMarineProvider : MarineForecastProvider {
        override val providerName = "ThrowingMarine"
        override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome = throw RuntimeException("down")
    }

    private class ThrowingAlertProvider : MarineAlertProvider {
        override suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome = throw RuntimeException("down")
    }

    private class ThrowingTideProvider : TideProvider {
        override suspend fun nearestStation(location: GeoPoint): TideStationOutcome = throw RuntimeException("down")
        override suspend fun events(stationId: String, date: LocalDate): TideEventsOutcome = throw RuntimeException("unreachable")
    }

    private class ThrowingObservationProvider : MarineObservationProvider {
        override val providerName = "ThrowingNDBC"
        override suspend fun nearestObservation(location: GeoPoint): MarineObservationOutcome = throw RuntimeException("down")
    }

    private class ThrowingCurrentProvider : CurrentProvider {
        override suspend fun nearestStation(location: GeoPoint): CurrentStationOutcome = throw RuntimeException("down")
        override suspend fun events(stationId: String, date: LocalDate): CurrentEventsOutcome = throw RuntimeException("unreachable")
    }

}
