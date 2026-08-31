package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BestWindowFinderTest {

    private val location = GeoPoint(28.408, -80.591)
    private val base = Instant.parse("2026-08-30T12:00:00Z")

    private fun point(
        hourOffset: Long,
        category: BoatingCategory,
        score: Int,
        conditions: MarineConditions? = null,
        hazards: List<Hazard> = emptyList(),
    ) = PointAssessment(
        at = base.plusSeconds(hourOffset * 3600),
        sample = RouteSample(location, RouteSampleRole.UNDERWAY, 0.0, base.plusSeconds(hourOffset * 3600)),
        conditions = conditions,
        category = category,
        score = score,
        hazards = hazards,
        confidence = Confidence.high(),
    )

    private fun conditionsAt(hourOffset: Long, windKts: Double? = null, waveFt: Double? = null) = MarineConditions(
        timestamp = base.plusSeconds(hourOffset * 3600),
        location = location,
        sustainedWindKts = windKts,
        waveHeightFt = waveFt,
        source = SourceReference("Test", null, base),
        confidence = Confidence.high(),
    )

    @Test
    fun `finds the longest contiguous GOOD-or-better span`() {
        val points = listOf(
            point(0, BoatingCategory.CAUTION, 60),
            point(1, BoatingCategory.GOOD, 75),
            point(2, BoatingCategory.EXCELLENT, 90),
            point(3, BoatingCategory.GOOD, 78),
            point(4, BoatingCategory.CAUTION, 55),
            point(5, BoatingCategory.EXCELLENT, 95),
        )
        val window = BestWindowFinder.find(points, base, base.plusSeconds(5 * 3600))!!
        assertEquals(base.plusSeconds(3600), window.start)
        assertEquals(base.plusSeconds(3 * 3600), window.end)
    }

    @Test
    fun `ties in length break toward the higher average score`() {
        val points = listOf(
            point(0, BoatingCategory.GOOD, 70), // span A: length 2, avg 72.5
            point(1, BoatingCategory.GOOD, 75),
            point(2, BoatingCategory.CAUTION, 50),
            point(3, BoatingCategory.EXCELLENT, 95), // span B: length 2, avg 92.5
            point(4, BoatingCategory.EXCELLENT, 90),
        )
        val window = BestWindowFinder.find(points, base, base.plusSeconds(4 * 3600))!!
        assertEquals(base.plusSeconds(3 * 3600), window.start)
    }

    @Test
    fun `no GOOD-or-better points at all returns null`() {
        val points = listOf(point(0, BoatingCategory.POOR, 30), point(1, BoatingCategory.NO_GO, 10))
        assertNull(BestWindowFinder.find(points, base, base.plusSeconds(3600)))
    }

    @Test
    fun `a window matching the planned start and end is flagged as matching, not a different recommendation`() {
        val points = listOf(
            point(0, BoatingCategory.GOOD, 75),
            point(1, BoatingCategory.EXCELLENT, 90),
        )
        val window = BestWindowFinder.find(points, points.first().at, points.last().at)!!
        assertTrue(window.matchesPlannedWindow)
    }

    @Test
    fun `a deteriorating afternoon produces a best window that ends before the planned return`() {
        val points = listOf(
            point(0, BoatingCategory.GOOD, 75, conditionsAt(0, windKts = 8.0)),
            point(1, BoatingCategory.EXCELLENT, 90, conditionsAt(1, windKts = 6.0)),
            point(
                2, BoatingCategory.NO_GO, 5, conditionsAt(2, windKts = 35.0),
                hazards = listOf(Hazard(HazardType.GUST, "Wind gusts reaching 35 kt", base.plusSeconds(2 * 3600), categoryCap = BoatingCategory.NO_GO)),
            ),
        )
        val plannedReturn = points.last().at
        val window = BestWindowFinder.find(points, points.first().at, plannedReturn)!!

        assertTrue("window should end before the planned return", window.end.isBefore(plannedReturn))
        assertFalse(window.matchesPlannedWindow)
        assertNotNull("should recommend returning earlier than planned", window.recommendReturnBy)
        assertEquals(window.end, window.recommendReturnBy)
        assertTrue("reasons should mention the deteriorating hazard", window.reasons.any { it.contains("35 kt") })
    }

    @Test
    fun `an unsafe planned return excludes the dangerous hour from the recommended window`() {
        val points = listOf(
            point(0, BoatingCategory.EXCELLENT, 95, conditionsAt(0, waveFt = 1.0)),
            point(1, BoatingCategory.EXCELLENT, 92, conditionsAt(1, waveFt = 1.2)),
            point(2, BoatingCategory.NO_GO, 0, conditionsAt(2, waveFt = 8.0)),
        )
        val window = BestWindowFinder.find(points, points.first().at, points.last().at)!!
        assertTrue(window.end.isBefore(points.last().at))
    }
}
