package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.observation.ComparisonStatus
import com.wakewindow.app.domain.observation.DisagreementType
import com.wakewindow.app.domain.observation.MarineDisagreement
import com.wakewindow.app.domain.observation.ObservationForecastComparison
import com.wakewindow.app.domain.observation.ObservationFreshness
import com.wakewindow.app.domain.observation.RepresentativenessLevel
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.observation.StationRepresentativeness
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.vessel.VesselProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Covers docs/MARINE_SCORING.md "Observation influence on assessment": a fresh, representative,
 * genuinely-worse-than-forecast observation may gate the departure point, but nothing else -
 * not a stale one, not an unrepresentative one, not one describing a moment far from departure,
 * and never one that's merely different (e.g. warmer) rather than worse.
 */
class ObservationalCautionEvaluatorTest {

    private val departureTime: Instant = Instant.parse("2026-08-30T16:00:00Z")
    private val vessel = VesselProfile.default()

    private fun station(freshness: ObservationFreshness = ObservationFreshness.FRESH) = SelectedMarineStation(
        stationId = "41009",
        name = "Canaveral 20 NM East",
        location = GeoPoint(28.5, -80.2),
        distanceNm = 4.0,
        observedAt = departureTime,
        ageMinutes = 10,
        freshness = freshness,
        hasWindData = true,
        hasWaveData = true,
        selectionReason = "Nearest fresh station with wave data",
    )

    private fun representativeness(level: RepresentativenessLevel) = StationRepresentativeness(level, emptyList())

    private fun comparison(
        observedAt: Instant = departureTime,
        representativenessLevel: RepresentativenessLevel = RepresentativenessLevel.HIGH,
        freshness: ObservationFreshness = ObservationFreshness.FRESH,
        disagreements: List<MarineDisagreement>,
    ) = ObservationForecastComparison(
        station = station(freshness),
        representativeness = representativeness(representativenessLevel),
        observedAt = observedAt,
        forecastValidAt = observedAt,
        disagreements = disagreements,
        status = ComparisonStatus.COMPARABLE,
    )

    private fun waveDisagreement(forecastFt: Double, observedFt: Double) = MarineDisagreement(
        DisagreementType.WAVE_HEIGHT, forecastFt, observedFt, "Observed seas differ from forecast",
    )

    @Test
    fun `a representative, materially worse wave observation gates the departure point`() {
        val comparison = comparison(disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 3.5)))
        val hazard = ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel)
        assertEquals(HazardType.OBSERVED_CONDITIONS, hazard?.type)
    }

    @Test
    fun `a wave observation at or beyond the vessel's own tolerance caps at POOR, not just CAUTION`() {
        // vessel.waveToleranceFt is 3.0 by default - an observed 3.5 ft exceeds it outright.
        val comparison = comparison(disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 3.5)))
        val hazard = ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel)
        assertEquals(BoatingCategory.POOR, hazard?.categoryCap)
    }

    @Test
    fun `a worse-than-forecast observation below the vessel's tolerance only caps at CAUTION`() {
        val comparison = comparison(disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 2.0)))
        val hazard = ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel)
        assertEquals(BoatingCategory.CAUTION, hazard?.categoryCap)
    }

    @Test
    fun `an observation that is BETTER than forecast never gates anything`() {
        val comparison = comparison(disagreements = listOf(waveDisagreement(forecastFt = 3.5, observedFt = 1.0)))
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }

    @Test
    fun `an unrelated field difference like temperature never gates anything`() {
        val tempDisagreement = MarineDisagreement(DisagreementType.TEMPERATURE, 70.0, 85.0, "Warmer than forecast")
        val comparison = comparison(disagreements = listOf(tempDisagreement))
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }

    @Test
    fun `LOW representativeness never gates the assessment, no matter how bad the disagreement`() {
        val comparison = comparison(
            representativenessLevel = RepresentativenessLevel.LOW,
            disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 5.0)),
        )
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }

    @Test
    fun `UNKNOWN representativeness never gates the assessment`() {
        val comparison = comparison(
            representativenessLevel = RepresentativenessLevel.UNKNOWN,
            disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 5.0)),
        )
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }

    @Test
    fun `an observation far outside the near-term window never gates a distant departure`() {
        val observedAt = departureTime.minusSeconds(6 * 3600) // 6 hours before departure
        val comparison = comparison(observedAt = observedAt, disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 5.0)))
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }

    @Test
    fun `a stale (unusable-freshness) comparison does not affect the assessment even if flagged representative`() {
        // Representativeness itself would already report LOW for UNUSABLE freshness (see
        // StationRepresentativenessEvaluatorTest) - this proves the caution evaluator doesn't
        // independently second-guess that and apply a hazard anyway.
        val comparison = comparison(
            representativenessLevel = RepresentativenessLevel.LOW,
            freshness = ObservationFreshness.UNUSABLE,
            disagreements = listOf(waveDisagreement(forecastFt = 1.0, observedFt = 5.0)),
        )
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }

    @Test
    fun `a null comparison (no station or observation at all) never gates anything`() {
        assertNull(ObservationalCautionEvaluator.evaluate(null, departureTime, vessel))
    }

    @Test
    fun `no disagreements at all means no caution`() {
        val comparison = comparison(disagreements = emptyList())
        assertNull(ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel))
    }
}
