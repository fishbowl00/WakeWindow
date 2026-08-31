package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.alert.MarineAlertSeverity
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.vessel.VesselProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarinePointScorerTest {

    private val at: Instant = Instant.parse("2026-08-30T16:00:00Z")
    private val location = GeoPoint(28.408, -80.591)
    private val defaultSample = RouteSample(location, RouteSampleRole.DEPARTURE, 0.0, at)
    private val defaultVessel = VesselProfile.default()

    private fun source() = SourceReference(sourceName = "Test", sourceUrl = null, retrievedAt = at)

    private fun conditions(
        windKts: Double? = null,
        gustKts: Double? = null,
        waveFt: Double? = null,
        wavePeriodSec: Double? = null,
        thunderstormPercent: Int? = null,
        precipPercent: Int? = null,
        visibilityNm: Double? = null,
        alerts: List<MarineAlert> = emptyList(),
    ) = MarineConditions(
        timestamp = at,
        location = location,
        sustainedWindKts = windKts,
        gustKts = gustKts,
        waveHeightFt = waveFt,
        wavePeriodSec = wavePeriodSec,
        thunderstormProbabilityPercent = thunderstormPercent,
        precipitationProbabilityPercent = precipPercent,
        visibilityNm = visibilityNm,
        marineAlerts = alerts,
        source = source(),
        confidence = Confidence.high(),
    )

    private fun alert(severity: MarineAlertSeverity, event: String = severity.name, vesselSizeExemptApplicable: Boolean = false) = MarineAlert(
        id = "test-$severity",
        event = event,
        headline = null,
        severity = severity,
        effective = at.minusSeconds(3600),
        expires = at.plusSeconds(3600),
        areaDescription = null,
        vesselSizeExemptApplicable = vesselSizeExemptApplicable,
    )

    @Test
    fun `calm conditions score EXCELLENT with no hazards`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(windKts = 8.0, gustKts = 10.0, waveFt = 1.0, precipPercent = 0),
            defaultVessel,
        )
        assertEquals(BoatingCategory.EXCELLENT, result.category)
        assertTrue("expected no hazards, got ${result.hazards}", result.hazards.isEmpty())
    }

    @Test
    fun `null conditions produce UNAVAILABLE, not a fabricated calm reading`() {
        val result = MarinePointScorer.score(defaultSample, null, defaultVessel)
        assertEquals(BoatingCategory.UNAVAILABLE, result.category)
        assertEquals(0, result.score)
    }

    @Test
    fun `gust at vessel tolerance gates to CAUTION`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(gustKts = defaultVessel.gustToleranceKts),
            defaultVessel,
        )
        assertEquals(BoatingCategory.CAUTION, result.category)
        assertTrue(result.hazards.any { it.type == HazardType.GUST })
    }

    @Test
    fun `gust well past tolerance gates to POOR`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(gustKts = defaultVessel.gustToleranceKts + 12),
            defaultVessel,
        )
        assertEquals(BoatingCategory.POOR, result.category)
    }

    @Test
    fun `wave height at 1_5x tolerance gates to NO_GO`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(waveFt = defaultVessel.waveToleranceFt * 1.5),
            defaultVessel,
        )
        assertEquals(BoatingCategory.NO_GO, result.category)
    }

    @Test
    fun `thunderstorm probability at 90 percent gates to NO_GO regardless of other factors`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(windKts = 5.0, waveFt = 0.5, thunderstormPercent = 90),
            defaultVessel,
        )
        assertEquals(BoatingCategory.NO_GO, result.category)
    }

    @Test
    fun `active extreme marine alert forces NO_GO even with otherwise perfect conditions`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(windKts = 5.0, waveFt = 0.5, gustKts = 6.0, alerts = listOf(alert(MarineAlertSeverity.EXTREME, "Special Marine Warning"))),
            defaultVessel,
        )
        assertEquals(BoatingCategory.NO_GO, result.category)
        assertTrue(result.hazards.any { it.type == HazardType.MARINE_ALERT_EXTREME })
    }

    @Test
    fun `active gale warning caps at POOR not just a point deduction`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(windKts = 5.0, waveFt = 0.5, alerts = listOf(alert(MarineAlertSeverity.SEVERE, "Gale Warning"))),
            defaultVessel,
        )
        assertEquals(BoatingCategory.POOR, result.category)
    }

    @Test
    fun `small craft advisory caps small vessel at CAUTION`() {
        val smallVessel = defaultVessel.copy(isSmallCraft = true)
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(windKts = 5.0, waveFt = 0.5, alerts = listOf(alert(MarineAlertSeverity.ADVISORY, "Small Craft Advisory", vesselSizeExemptApplicable = true))),
            smallVessel,
        )
        assertEquals(BoatingCategory.CAUTION, result.category)
    }

    @Test
    fun `small craft advisory is only a deduction for a vessel that is not small craft`() {
        val largeVessel = defaultVessel.copy(isSmallCraft = false)
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(windKts = 5.0, waveFt = 0.5, alerts = listOf(alert(MarineAlertSeverity.ADVISORY, "Small Craft Advisory", vesselSizeExemptApplicable = true))),
            largeVessel,
        )
        // Calm underlying conditions plus a 10-point deduction should still clear CAUTION's
        // floor - the advisory must not be gated for a vessel that isn't "small craft."
        assertTrue("expected better than CAUTION, got ${result.category}", result.category.severityRank < BoatingCategory.CAUTION.severityRank)
    }

    @Test
    fun `same wind reads differently for vessels with different tolerances`() {
        val touringVessel = defaultVessel.copy(gustToleranceKts = 40.0)
        val pwc = defaultVessel.copy(gustToleranceKts = 18.0)

        val touringResult = MarinePointScorer.score(defaultSample, conditions(gustKts = 22.0), touringVessel)
        val pwcResult = MarinePointScorer.score(defaultSample, conditions(gustKts = 22.0), pwc)

        assertTrue(touringResult.category.severityRank < pwcResult.category.severityRank)
    }

    @Test
    fun `visibility below tolerance gates to CAUTION or worse`() {
        val result = MarinePointScorer.score(
            defaultSample,
            conditions(visibilityNm = 0.5, windKts = 5.0, waveFt = 0.5),
            defaultVessel,
        )
        assertTrue(result.category.severityRank >= BoatingCategory.CAUTION.severityRank)
        assertTrue(result.hazards.any { it.type == HazardType.VISIBILITY })
    }

    @Test
    fun `general-weather-only reading (no marine data) reduces confidence rather than claiming full confidence`() {
        val generalOnly = conditions(windKts = 8.0).copy(confidence = Confidence.high())
        val result = MarinePointScorer.score(defaultSample, generalOnly, defaultVessel)
        assertNull(generalOnly.waveHeightFt)
        assertTrue(result.confidence.reasons.isNotEmpty())
    }
}
