package com.wakewindow.app.domain.scoring

import com.wakewindow.app.data.remote.nws.NwsMapper
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Covers the product's central claim: a beautiful morning followed by a dangerous
 * afternoon/return must not be flattened into an "excellent" day by averaging - see
 * docs/MARINE_SCORING.md "Window-level aggregation."
 */
class MarineScoreEngineTest {

    private val location = GeoPoint(28.408, -80.591)
    private val vessel = VesselProfile.default()

    private fun source(at: Instant) = SourceReference(sourceName = "Test", sourceUrl = null, retrievedAt = at)

    private fun calm(at: Instant) = MarineConditions(
        timestamp = at,
        location = location,
        sustainedWindKts = 8.0,
        gustKts = 10.0,
        waveHeightFt = 1.0,
        thunderstormProbabilityPercent = 5,
        source = source(at),
        confidence = Confidence.high(),
    )

    private fun dangerousThunderstorm(at: Instant) = MarineConditions(
        timestamp = at,
        location = location,
        sustainedWindKts = 20.0,
        gustKts = 28.0,
        waveHeightFt = 2.0,
        thunderstormProbabilityPercent = 85,
        marineAlerts = listOf(
            MarineAlert(
                id = "special-marine-warning",
                event = "Special Marine Warning",
                headline = null,
                severity = MarineAlertSeverity.EXTREME,
                effective = at.minusSeconds(600),
                expires = at.plusSeconds(3600),
                areaDescription = null,
                impact = NwsMapper.classify("Special Marine Warning").impact,
            ),
        ),
        source = source(at),
        confidence = Confidence.high(),
    )

    /** 8am departure, hourly samples through a 4pm return; the day is calm except a severe
     * storm arriving right at the planned return. */
    private fun samplesWithDangerousReturn(): Pair<List<RouteSample>, Map<Instant, MarineConditions>> {
        val departure = Instant.parse("2026-08-30T12:00:00Z") // 08:00 local (UTC-4)
        val returnTime = Instant.parse("2026-08-30T20:00:00Z") // 16:00 local
        val hours = (0..8).map { departure.plusSeconds(it * 3600L) }
        val conditionsByHour = hours.associateWith { hour ->
            if (hour == returnTime) dangerousThunderstorm(hour) else calm(hour)
        }
        val samples = hours.mapIndexed { index, hour ->
            val role = when (hour) {
                departure -> RouteSampleRole.DEPARTURE
                returnTime -> RouteSampleRole.RETURN
                else -> RouteSampleRole.UNDERWAY
            }
            RouteSample(location, role, index / 8.0, hour)
        }
        return samples to conditionsByHour
    }

    @Test
    fun `a dangerous return is not averaged away by a calm morning`() {
        val (samples, conditions) = samplesWithDangerousReturn()
        val assessment = MarineScoreEngine.assess(samples, { conditions[it.estimatedTime] }, vessel)

        assertEquals(BoatingCategory.NO_GO, assessment.overallAssessment.category)
        assertEquals(BoatingCategory.NO_GO, assessment.returnAssessment.category)
        assertEquals(BoatingCategory.EXCELLENT, assessment.departureAssessment.category)
    }

    @Test
    fun `overall score is weighted down by return-proximate hazards more than distant ones`() {
        val departure = Instant.parse("2026-08-30T12:00:00Z")
        val returnTime = Instant.parse("2026-08-30T20:00:00Z")
        val hours = (0..8).map { departure.plusSeconds(it * 3600L) }

        // Hazard near departure instead of near return, same severity.
        val conditionsHazardEarly = hours.associateWith { hour ->
            if (hour == departure.plusSeconds(3600)) dangerousThunderstorm(hour) else calm(hour)
        }
        val conditionsHazardLate = hours.associateWith { hour ->
            if (hour == returnTime.minusSeconds(3600)) dangerousThunderstorm(hour) else calm(hour)
        }
        val samples = hours.mapIndexed { index, hour ->
            val role = when (hour) {
                departure -> RouteSampleRole.DEPARTURE
                returnTime -> RouteSampleRole.RETURN
                else -> RouteSampleRole.UNDERWAY
            }
            RouteSample(location, role, index / 8.0, hour)
        }

        val earlyHazardAssessment = MarineScoreEngine.assess(samples, { conditionsHazardEarly[it.estimatedTime] }, vessel)
        val lateHazardAssessment = MarineScoreEngine.assess(samples, { conditionsHazardLate[it.estimatedTime] }, vessel)

        assertTrue(
            "hazard closer to return should produce a lower overall score",
            lateHazardAssessment.overallAssessment.score <= earlyHazardAssessment.overallAssessment.score,
        )
    }

    @Test
    fun `an entirely calm outing scores EXCELLENT with a best window spanning the whole plan`() {
        val departure = Instant.parse("2026-08-30T12:00:00Z")
        val returnTime = Instant.parse("2026-08-30T16:00:00Z")
        val hours = (0..4).map { departure.plusSeconds(it * 3600L) }
        val conditions = hours.associateWith { calm(it) }
        val samples = hours.mapIndexed { index, hour ->
            val role = when (hour) {
                departure -> RouteSampleRole.DEPARTURE
                returnTime -> RouteSampleRole.RETURN
                else -> RouteSampleRole.UNDERWAY
            }
            RouteSample(location, role, index / 4.0, hour)
        }

        val assessment = MarineScoreEngine.assess(samples, { conditions[it.estimatedTime] }, vessel)

        assertEquals(BoatingCategory.EXCELLENT, assessment.overallAssessment.category)
        assertTrue(assessment.bestWindow != null)
        assertEquals(departure, assessment.bestWindow!!.start)
        assertEquals(returnTime, assessment.bestWindow!!.end)
    }

    @Test
    fun `missing conditions for every sample produces an UNAVAILABLE overall category, not a fabricated one`() {
        val departure = Instant.parse("2026-08-30T12:00:00Z")
        val returnTime = Instant.parse("2026-08-30T16:00:00Z")
        val samples = listOf(
            RouteSample(location, RouteSampleRole.DEPARTURE, 0.0, departure),
            RouteSample(location, RouteSampleRole.RETURN, 1.0, returnTime),
        )

        val assessment = MarineScoreEngine.assess(samples, { null }, vessel)

        assertEquals(BoatingCategory.UNAVAILABLE, assessment.overallAssessment.category)
    }

    @Test
    fun `an alert active across every sampled hour appears once in worstHazards, not once per hour`() {
        // Regression test for a real bug found during Sprint 3's live Clinton Lake
        // re-validation: a nine-hour outing under one continuous Heat Advisory produced nine
        // identical "Heat Advisory in effect" entries in worstHazards, because the old dedup
        // key included the hour - see docs/ASSESSMENT_VALIDATION.md.
        val departure = Instant.parse("2026-08-30T12:00:00Z")
        val returnTime = Instant.parse("2026-08-30T20:00:00Z")
        val hours = (0..8).map { departure.plusSeconds(it * 3600L) }
        val heatAdvisory = MarineAlert(
            id = "heat-advisory", event = "Heat Advisory", headline = null, severity = MarineAlertSeverity.ADVISORY,
            effective = departure.minusSeconds(3600), expires = returnTime.plusSeconds(3600), areaDescription = null,
            impact = NwsMapper.classify("Heat Advisory").impact,
        )
        val conditionsByHour = hours.associateWith { hour -> calm(hour).copy(marineAlerts = listOf(heatAdvisory)) }
        val samples = hours.mapIndexed { index, hour ->
            val role = when (hour) {
                departure -> RouteSampleRole.DEPARTURE
                returnTime -> RouteSampleRole.RETURN
                else -> RouteSampleRole.UNDERWAY
            }
            RouteSample(location, role, index / 8.0, hour)
        }

        val assessment = MarineScoreEngine.assess(samples, { conditionsByHour[it.estimatedTime] }, vessel)

        val heatHazards = assessment.worstHazards.filter { it.message.contains("Heat Advisory") }
        assertEquals(1, heatHazards.size)
    }
}
