package com.wakewindow.app.data.remote.nws

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NwsMapperTest {

    private val location = GeoPoint(28.30, -80.30)
    private fun source(at: Instant) = SourceReference("National Weather Service", null, at)

    @Test
    fun `parseNwsInstant handles a numeric zone offset that Instant_parse itself rejects`() {
        val instant = NwsMapper.parseNwsInstant("2026-08-31T07:00:16-04:00")
        // 07:00:16 -04:00 is 11:00:16 UTC.
        assertEquals(Instant.parse("2026-08-31T11:00:16Z"), instant)
    }

    @Test
    fun `a multi-hour grid interval value applies to every hour it spans`() {
        // A real NWS waveHeight value observed live: "2026-08-30T18:00:00+00:00/PT6H" - one
        // value covering six hours, not a per-hour array like /forecast/hourly.
        val properties = NwsGridpointsProperties(
            waveHeight = NwsGridQuantitative(
                uom = "wmoUnit:m",
                values = listOf(NwsGridValue(validTime = "2026-08-30T18:00:00+00:00/PT6H", value = 0.9144)),
            ),
        )
        val hours = listOf(
            Instant.parse("2026-08-30T18:00:00Z"),
            Instant.parse("2026-08-30T20:00:00Z"),
            Instant.parse("2026-08-30T23:00:00Z"),
        )
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, hours, location, source(hours.first()))

        // 0.9144 m = 3.0 ft, applied to every hour within the 6-hour span.
        result.forEach { assertEquals(3.0, it.waveHeightFt!!, 0.01) }
    }

    @Test
    fun `an hour with no covering interval leaves the field null rather than reusing a stale value`() {
        val properties = NwsGridpointsProperties(
            waveHeight = NwsGridQuantitative(
                uom = "wmoUnit:m",
                values = listOf(NwsGridValue(validTime = "2026-08-30T18:00:00+00:00/PT1H", value = 1.0)),
            ),
        )
        val hourOutsideCoverage = Instant.parse("2026-08-31T04:00:00Z")
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, listOf(hourOutsideCoverage), location, source(hourOutsideCoverage))
        assertNull(result.single().waveHeightFt)
    }

    @Test
    fun `wind speed and gust are converted from km-h to knots`() {
        val hour = Instant.parse("2026-08-30T17:00:00Z")
        val properties = NwsGridpointsProperties(
            windSpeed = NwsGridQuantitative(values = listOf(NwsGridValue("2026-08-30T17:00:00+00:00/PT1H", 18.52))), // ~10 kt
        )
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, listOf(hour), location, source(hour)).single()
        assertEquals(10.0, result.sustainedWindKts!!, 0.5)
    }

    @Test
    fun `thunderstorm probability passes through as a raw percent with no unit conversion`() {
        val hour = Instant.parse("2026-08-30T17:00:00Z")
        val properties = NwsGridpointsProperties(
            probabilityOfThunder = NwsGridQuantitative(values = listOf(NwsGridValue("2026-08-30T17:00:00+00:00/PT1H", 40.0))),
        )
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, listOf(hour), location, source(hour)).single()
        assertEquals(40, result.thunderstormProbabilityPercent)
    }

    @Test
    fun `classifySeverity maps extreme, severe, and advisory marine events correctly`() {
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME, NwsMapper.classifySeverity("Special Marine Warning"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME, NwsMapper.classifySeverity("Hurricane Warning"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.SEVERE, NwsMapper.classifySeverity("Gale Warning"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.ADVISORY, NwsMapper.classifySeverity("Small Craft Advisory"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.WATCH, NwsMapper.classifySeverity("Coastal Flood Watch"))
    }

    @Test
    fun `small craft advisory is classified as vessel-size exempt`() {
        val classification = NwsMapper.classify("Small Craft Advisory")
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.ADVISORY, classification.severity)
        assertTrue(classification.vesselSizeExemptApplicable)
    }

    @Test
    fun `dense fog advisory is classified as NOT vessel-size exempt - fog is dangerous to any size vessel`() {
        val classification = NwsMapper.classify("Dense Fog Advisory")
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.ADVISORY, classification.severity)
        assertFalse(classification.vesselSizeExemptApplicable)
    }

    @Test
    fun `severe thunderstorm warning is classified as EXTREME, more serious than a generic warning`() {
        val classification = NwsMapper.classify("Severe Thunderstorm Warning")
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME, classification.severity)
    }

    @Test
    fun `gale warning is classified as SEVERE and not vessel-size exempt`() {
        val classification = NwsMapper.classify("Gale Warning")
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.SEVERE, classification.severity)
        assertFalse(classification.vesselSizeExemptApplicable)
    }

    @Test
    fun `an alert with no onset or expiry is treated as active (fail-unsafe, not fail-open)`() {
        val alert = com.wakewindow.app.domain.alert.MarineAlert(
            id = "x", event = "Special Marine Warning", headline = null,
            severity = com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME,
            effective = null, expires = null, areaDescription = null,
            impact = NwsMapper.classify("Special Marine Warning").impact,
        )
        assertTrue(alert.isActiveAt(Instant.now()))
    }

    // --- Alert relevance model (docs/MARINE_SCORING.md "Alert relevance model") - Sprint 3
    // replaces a blanket "any advisory/warning caps the category" policy with a per-event-type
    // table of consequences. Each case below is a real event type this app must classify. ---

    @Test
    fun `hurricane and tropical storm warnings are a hard gate to NO_GO`() {
        for (event in listOf("Hurricane Warning", "Tropical Storm Warning")) {
            val impact = NwsMapper.classify(event).impact
            assertEquals(event, com.wakewindow.app.domain.alert.AlertImpactBehavior.HARD_GATE, impact.behavior)
            assertEquals(event, com.wakewindow.app.domain.alert.AlertSeverityCap.NO_GO, impact.categoryCap)
        }
    }

    @Test
    fun `special marine warning and severe convective warnings are a hard gate to NO_GO`() {
        for (event in listOf("Special Marine Warning", "Severe Thunderstorm Warning", "Tornado Warning")) {
            val impact = NwsMapper.classify(event).impact
            assertEquals(event, com.wakewindow.app.domain.alert.AlertImpactBehavior.HARD_GATE, impact.behavior)
            assertEquals(event, com.wakewindow.app.domain.alert.AlertSeverityCap.NO_GO, impact.categoryCap)
        }
    }

    @Test
    fun `gale and storm warnings ceiling the category at POOR`() {
        for (event in listOf("Gale Warning", "Storm Warning")) {
            val impact = NwsMapper.classify(event).impact
            assertEquals(event, com.wakewindow.app.domain.alert.AlertImpactBehavior.CATEGORY_CEILING, impact.behavior)
            assertEquals(event, com.wakewindow.app.domain.alert.AlertSeverityCap.POOR, impact.categoryCap)
        }
    }

    @Test
    fun `small craft advisory always ceilings at CAUTION - the impact model carries no vessel-size exemption`() {
        val impact = NwsMapper.classify("Small Craft Advisory").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.CATEGORY_CEILING, impact.behavior)
        assertEquals(com.wakewindow.app.domain.alert.AlertSeverityCap.CAUTION, impact.categoryCap)
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactCategory.MARINE_NAVIGATION, impact.category)
    }

    @Test
    fun `dense fog advisory ceilings at CAUTION - dangerous to any vessel size`() {
        val impact = NwsMapper.classify("Dense Fog Advisory").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.CATEGORY_CEILING, impact.behavior)
        assertEquals(com.wakewindow.app.domain.alert.AlertSeverityCap.CAUTION, impact.categoryCap)
    }

    @Test
    fun `an active coastal flood advisory or warning is a COASTAL_ACCESS ceiling`() {
        val impact = NwsMapper.classify("Coastal Flood Advisory").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactCategory.COASTAL_ACCESS, impact.category)
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.CATEGORY_CEILING, impact.behavior)
    }

    @Test
    fun `a coastal flood WATCH is not yet a CATEGORY_CEILING - it hasn't happened yet`() {
        val impact = NwsMapper.classify("Coastal Flood Watch").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.SCORE_DEDUCTION, impact.behavior)
    }

    @Test
    fun `excessive heat warning is a HUMAN_EXPOSURE ceiling, distinct from a marine-navigation hazard`() {
        val impact = NwsMapper.classify("Excessive Heat Warning").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactCategory.HUMAN_EXPOSURE, impact.category)
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.CATEGORY_CEILING, impact.behavior)
    }

    @Test
    fun `a plain heat advisory is only a deduction, not a category ceiling like Sprint 2's blanket policy`() {
        val impact = NwsMapper.classify("Heat Advisory").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.SCORE_DEDUCTION, impact.behavior)
        assertTrue(impact.scoreDeduction > 0.0)
    }

    @Test
    fun `wind chill, freeze, frost, and cold weather advisories are HUMAN_EXPOSURE deductions`() {
        for (event in listOf("Wind Chill Advisory", "Freeze Warning", "Frost Advisory", "Cold Weather Advisory")) {
            val impact = NwsMapper.classify(event).impact
            assertEquals(event, com.wakewindow.app.domain.alert.AlertImpactCategory.HUMAN_EXPOSURE, impact.category)
            assertEquals(event, com.wakewindow.app.domain.alert.AlertImpactBehavior.SCORE_DEDUCTION, impact.behavior)
        }
    }

    @Test
    fun `air quality alerts are informational only - no defensible boating-safety consequence`() {
        val impact = NwsMapper.classify("Air Quality Alert").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.INFORMATIONAL_ONLY, impact.behavior)
    }

    @Test
    fun `an unrecognized warning-tier alert still applies a moderate ceiling, never silently dropped`() {
        val impact = NwsMapper.classify("Some Future Marine Warning").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactCategory.UNKNOWN, impact.category)
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.CATEGORY_CEILING, impact.behavior)
    }

    @Test
    fun `an unrecognized advisory-tier alert is a deduction, not a full ceiling`() {
        val impact = NwsMapper.classify("Some Future Advisory").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.SCORE_DEDUCTION, impact.behavior)
    }

    @Test
    fun `a completely unrecognized event is surfaced as informational, never silently dropped`() {
        val impact = NwsMapper.classify("Some Unrelated Statement").impact
        assertEquals(com.wakewindow.app.domain.alert.AlertImpactBehavior.INFORMATIONAL_ONLY, impact.behavior)
    }
}
