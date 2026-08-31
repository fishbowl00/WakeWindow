package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BestWindowFinderTest {

    private val location = GeoPoint(28.408, -80.591)
    private val base = Instant.parse("2026-08-30T12:00:00Z")

    private fun point(hourOffset: Long, category: BoatingCategory, score: Int) = PointAssessment(
        at = base.plusSeconds(hourOffset * 3600),
        sample = RouteSample(location, RouteSampleRole.UNDERWAY, 0.0, base.plusSeconds(hourOffset * 3600)),
        conditions = null,
        category = category,
        score = score,
        hazards = emptyList(),
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
        val window = BestWindowFinder.find(points)!!
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
        val window = BestWindowFinder.find(points)!!
        assertEquals(base.plusSeconds(3 * 3600), window.start)
    }

    @Test
    fun `no GOOD-or-better points at all returns null`() {
        val points = listOf(point(0, BoatingCategory.POOR, 30), point(1, BoatingCategory.NO_GO, 10))
        assertNull(BestWindowFinder.find(points))
    }
}
