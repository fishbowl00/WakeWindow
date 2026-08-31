package com.wakewindow.app.data.repository

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.alert.MarineAlertSeverity
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
import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.route.BoatingPlan
import com.wakewindow.app.domain.scoring.BoatingCategory
import com.wakewindow.app.domain.tide.TideEventsOutcome
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStationOutcome
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Integration-level tests through the real [DefaultBoatingRepository], using hand-written
 * fakes for every provider interface rather than a mocking framework - matching RideCast's
 * own testing philosophy (see docs/RIDECAST_REFERENCE_AUDIT.md section 13). These exist
 * specifically to prove the missing-data and alert-gating policy documented in
 * docs/ASSESSMENT_VALIDATION.md holds at the real integration boundary, not only inside
 * [com.wakewindow.app.domain.scoring.MarinePointScorer] in isolation.
 */
class DefaultBoatingRepositoryTest {

    private val location = GeoPoint(28.408, -80.591)
    private val departure = Instant.parse("2026-08-30T12:00:00Z")
    private val returnTime = Instant.parse("2026-08-30T16:00:00Z")

    private fun plan(vessel: VesselProfile = VesselProfile.default()) = BoatingPlan(
        launch = MarinePlace(id = "1", discovery = MarinePlaceCandidate("Test Launch", location, null, MarinePlaceType.PORT)),
        departureTime = departure,
        returnTime = returnTime,
        vessel = vessel,
        zoneId = ZoneId.of("UTC"),
    )

    private fun calmSeries(start: Instant, end: Instant, sourceName: String = "Fake") = generateSequence(start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
        .takeWhile { !it.isAfter(end) }
        .map { hour ->
            MarineConditions(
                timestamp = hour, location = location,
                sustainedWindKts = 8.0, gustKts = 10.0, waveHeightFt = 1.0, thunderstormProbabilityPercent = 5,
                source = SourceReference(sourceName, null, hour), confidence = Confidence.high(),
            )
        }.toList()

    private class FakeGeneralProvider(private val result: ForecastOutcome) : GeneralWeatherProvider {
        override val providerName = "FakeGeneral"
        override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant) = result
    }

    private class FakeMarineProvider(private val result: ForecastOutcome) : MarineForecastProvider {
        override val providerName = "FakeMarine"
        override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant) = result
    }

    private class FakeAlertProvider(private val result: MarineAlertOutcome) : MarineAlertProvider {
        override suspend fun activeAlerts(location: GeoPoint) = result
    }

    private class FakeTideProvider(private val stationOutcome: TideStationOutcome = TideStationOutcome.NotTidal) : TideProvider {
        override suspend fun nearestStation(location: GeoPoint) = stationOutcome
        override suspend fun events(stationId: String, date: LocalDate) = TideEventsOutcome.Success(emptyList())
    }

    private class FakeObservationProvider(private val result: MarineObservationOutcome) : MarineObservationProvider {
        override val providerName = "FakeNDBC"
        override suspend fun nearestObservation(location: GeoPoint) = result
    }

    private fun repository(
        general: ForecastOutcome,
        marine: ForecastOutcome,
        alerts: MarineAlertOutcome,
        observation: MarineObservationOutcome = MarineObservationOutcome.NoStationAvailable,
    ) = DefaultBoatingRepository(
        generalProviders = listOf(FakeGeneralProvider(general)),
        marineForecastProviders = listOf(FakeMarineProvider(marine)),
        alertProvider = FakeAlertProvider(alerts),
        tideProvider = FakeTideProvider(),
        observationProvider = FakeObservationProvider(observation),
    )

    @Test
    fun `missing wave data across every provider does not silently score as calm seas`() = runBlocking {
        val windOnly = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600)).map { it.copy(waveHeightFt = null) }
        val repo = repository(
            general = ForecastOutcome.Success(windOnly),
            marine = ForecastOutcome.Success(emptyList()), // no marine provider data at all
            alerts = MarineAlertOutcome.Success(emptyList()),
        )
        val assessment = repo.buildAssessment(plan())

        // No wave hazard should fire (there's no data to base one on) - but confidence must
        // say so explicitly rather than reporting HIGH confidence in a wave-less picture.
        assertTrue(assessment.departureAssessment.hazards.none { it.type.name.contains("WAVE") })
        assertTrue(
            "expected a reason mentioning missing marine data",
            assessment.confidence.reasons.any { it.contains("marine", ignoreCase = true) || it.contains("wave", ignoreCase = true) },
        )
    }

    @Test
    fun `a failed alert check reduces confidence rather than presenting as a confirmed clear`() = runBlocking {
        val calm = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val repo = repository(
            general = ForecastOutcome.Success(calm),
            marine = ForecastOutcome.Success(calm),
            alerts = MarineAlertOutcome.Failure("NWS alerts endpoint timed out"),
        )
        val assessment = repo.buildAssessment(plan())

        assertTrue(assessment.confidence.level != ConfidenceLevel.HIGH)
        assertTrue(assessment.confidence.reasons.any { it.contains("alert", ignoreCase = true) })
    }

    @Test
    fun `a special marine warning overlapping the outing caps the assessment at NO_GO end to end`() = runBlocking {
        val calm = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val warning = MarineAlert(
            id = "smw", event = "Special Marine Warning", headline = null, severity = MarineAlertSeverity.EXTREME,
            effective = departure.plusSeconds(1800), expires = departure.plusSeconds(7200), areaDescription = null,
        )
        val repo = repository(
            general = ForecastOutcome.Success(calm),
            marine = ForecastOutcome.Success(calm),
            alerts = MarineAlertOutcome.Success(listOf(warning)),
        )
        val assessment = repo.buildAssessment(plan())

        assertEquals(BoatingCategory.NO_GO, assessment.overallAssessment.category)
    }

    @Test
    fun `an alert that already expired before departure does not gate the assessment`() = runBlocking {
        val calm = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val expiredWarning = MarineAlert(
            id = "old", event = "Special Marine Warning", headline = null, severity = MarineAlertSeverity.EXTREME,
            effective = departure.minusSeconds(3 * 3600), expires = departure.minusSeconds(3600), areaDescription = null,
        )
        val repo = repository(
            general = ForecastOutcome.Success(calm),
            marine = ForecastOutcome.Success(calm),
            alerts = MarineAlertOutcome.Success(listOf(expiredWarning)),
        )
        val assessment = repo.buildAssessment(plan())

        assertEquals(BoatingCategory.EXCELLENT, assessment.overallAssessment.category)
    }

    @Test
    fun `a future alert starting after the plan's return does not gate the assessment`() = runBlocking {
        val calm = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val futureWarning = MarineAlert(
            id = "future", event = "Special Marine Warning", headline = null, severity = MarineAlertSeverity.EXTREME,
            effective = returnTime.plusSeconds(3 * 3600), expires = returnTime.plusSeconds(6 * 3600), areaDescription = null,
        )
        val repo = repository(
            general = ForecastOutcome.Success(calm),
            marine = ForecastOutcome.Success(calm),
            alerts = MarineAlertOutcome.Success(listOf(futureWarning)),
        )
        val assessment = repo.buildAssessment(plan())

        assertEquals(BoatingCategory.EXCELLENT, assessment.overallAssessment.category)
    }

    @Test
    fun `total marine provider failure still produces a result instead of crashing, with reduced confidence`() = runBlocking {
        val calmGeneral = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val repo = repository(
            general = ForecastOutcome.Success(calmGeneral),
            marine = ForecastOutcome.Failure("marine provider down"),
            alerts = MarineAlertOutcome.Success(emptyList()),
        )
        val assessment = repo.buildAssessment(plan())

        assertTrue(assessment.overallAssessment.category != BoatingCategory.UNAVAILABLE) // general data alone is enough to score
        assertTrue(assessment.confidence.level != ConfidenceLevel.HIGH)
    }

    @Test
    fun `a fresh buoy observation disagreeing with the forecast is surfaced and reduces confidence`() = runBlocking {
        val calmForecast = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val station = SelectedMarineStation(
            stationId = "41113", name = "Cape Canaveral Nearshore", location = location, distanceNm = 5.0,
            observedAt = departure.minusSeconds(600), ageMinutes = 10, freshness = ObservationFreshness.FRESH,
            hasWindData = false, hasWaveData = true, selectionReason = "test",
        )
        val observedConditions = MarineConditions(
            timestamp = departure.minusSeconds(600), location = location,
            waveHeightFt = 6.0, // materially higher than the 1.0 ft forecast
            source = SourceReference("FakeNDBC", null, departure), observationAgeMinutes = 10, confidence = Confidence.high(),
        )
        val repo = repository(
            general = ForecastOutcome.Success(calmForecast),
            marine = ForecastOutcome.Success(calmForecast),
            alerts = MarineAlertOutcome.Success(emptyList()),
            observation = MarineObservationOutcome.Success(station, observedConditions),
        )
        val assessment = repo.buildAssessment(plan())

        assertTrue(assessment.disagreements.any { it.type == com.wakewindow.app.domain.observation.DisagreementType.WAVE_HEIGHT })
        assertEquals("41113", assessment.nearestObservationStation?.stationId)
    }

    @Test
    fun `a stale buoy observation is not used for disagreement detection`() = runBlocking {
        val calmForecast = calmSeries(departure.minusSeconds(3600), returnTime.plusSeconds(3600))
        val station = SelectedMarineStation(
            stationId = "41113", name = "Cape Canaveral Nearshore", location = location, distanceNm = 5.0,
            observedAt = departure.minusSeconds(5 * 3600), ageMinutes = 300, freshness = ObservationFreshness.UNUSABLE,
            hasWindData = false, hasWaveData = true, selectionReason = "test",
        )
        val observedConditions = MarineConditions(
            timestamp = departure.minusSeconds(5 * 3600), location = location,
            waveHeightFt = 6.0,
            source = SourceReference("FakeNDBC", null, departure), observationAgeMinutes = 300, confidence = Confidence.high(),
        )
        val repo = repository(
            general = ForecastOutcome.Success(calmForecast),
            marine = ForecastOutcome.Success(calmForecast),
            alerts = MarineAlertOutcome.Success(emptyList()),
            observation = MarineObservationOutcome.Success(station, observedConditions),
        )
        val assessment = repo.buildAssessment(plan())

        assertTrue(assessment.disagreements.isEmpty())
    }
}
