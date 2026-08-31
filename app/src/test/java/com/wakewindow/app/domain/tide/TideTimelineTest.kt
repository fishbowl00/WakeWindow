package com.wakewindow.app.domain.tide

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TideTimelineTest {

    private val location = GeoPoint(28.408, -80.591)
    private fun source() = SourceReference("NOAA Tides & Currents", null, Instant.parse("2026-08-30T00:00:00Z"))

    private val low = TideEvent(TideEventType.LOW, Instant.parse("2026-08-30T09:00:00Z"), 0.2)
    private val high = TideEvent(TideEventType.HIGH, Instant.parse("2026-08-30T15:00:00Z"), 4.0)
    private val events = listOf(low, high)

    private fun trendAt(instant: Instant, allEvents: List<TideEvent> = events): TideTrend? =
        TideTimeline.conditionsAt(allEvents, listOf(instant), location, source()).single().tideTrend

    @Test
    fun `height at a known event time matches that event's height exactly`() {
        val result = TideTimeline.conditionsAt(events, listOf(low.time), location, source())
        assertEquals(0.2, result.single().tideHeightFt!!, 0.01)
    }

    @Test
    fun `height at the midpoint between low and high is between the two heights`() {
        val midpoint = Instant.parse("2026-08-30T12:00:00Z")
        val result = TideTimeline.conditionsAt(events, listOf(midpoint), location, source())
        val height = result.single().tideHeightFt!!
        assertTrue("expected height between 0.2 and 4.0, got $height", height in 0.2..4.0)
    }

    @Test
    fun `trend well between a low and a following high is RISING`() {
        assertEquals(TideTrend.RISING, trendAt(Instant.parse("2026-08-30T12:00:00Z")))
    }

    @Test
    fun `trend well between a high and a following low is FALLING`() {
        val secondLow = TideEvent(TideEventType.LOW, Instant.parse("2026-08-30T21:00:00Z"), 0.5)
        val fullDay = listOf(low, high, secondLow)
        assertEquals(TideTrend.FALLING, trendAt(Instant.parse("2026-08-30T18:00:00Z"), fullDay))
    }

    @Test
    fun `trend within 45 minutes of a charted high is NEAR_HIGH, not RISING`() {
        assertEquals(TideTrend.NEAR_HIGH, trendAt(high.time.minusSeconds(20 * 60)))
        assertEquals(TideTrend.NEAR_HIGH, trendAt(high.time))
        assertEquals(TideTrend.NEAR_HIGH, trendAt(high.time.plusSeconds(20 * 60)))
    }

    @Test
    fun `trend within 45 minutes of a charted low is NEAR_LOW, not FALLING or RISING`() {
        assertEquals(TideTrend.NEAR_LOW, trendAt(low.time.minusSeconds(30 * 60)))
        assertEquals(TideTrend.NEAR_LOW, trendAt(low.time))
        assertEquals(TideTrend.NEAR_LOW, trendAt(low.time.plusSeconds(30 * 60)))
    }

    @Test
    fun `next high and next low tide are the soonest future events of each type`() {
        val secondLow = TideEvent(TideEventType.LOW, Instant.parse("2026-08-30T21:00:00Z"), 0.5)
        val fullDay = listOf(low, high, secondLow)
        val before = Instant.parse("2026-08-30T08:00:00Z")
        val result = TideTimeline.conditionsAt(fullDay, listOf(before), location, source()).single()
        assertEquals(low.time, result.nextLowTide!!.time)
        assertEquals(high.time, result.nextHighTide!!.time)
    }

    /** Regression test for the exact Sprint 1 bug: an hour before the fetched window's first
     * known event used to fall through to a bare `null` trend, which the UI rendered as an
     * unexplained blank dash. It must now resolve to an explicit direction. */
    @Test
    fun `an hour before the first known event resolves to an explicit trend, never a bare null`() {
        val beforeFirstEvent = Instant.parse("2026-08-30T00:00:00Z")
        val trend = trendAt(beforeFirstEvent)
        assertNotEquals(null, trend)
        // Heading toward the first known event, which is a LOW - so the tide is falling.
        assertEquals(TideTrend.FALLING, trend)
    }

    @Test
    fun `an hour after the last known event resolves to an explicit trend, never a bare null`() {
        val afterLastEvent = Instant.parse("2026-08-30T23:00:00Z")
        val trend = trendAt(afterLastEvent)
        assertNotEquals(null, trend)
        // Moving away from the last known event, which is a HIGH - so the tide is falling.
        assertEquals(TideTrend.FALLING, trend)
    }

    @Test
    fun `crossing a UTC day boundary still resolves an explicit trend`() {
        val secondLow = TideEvent(TideEventType.LOW, Instant.parse("2026-08-31T02:00:00Z"), 0.6)
        val fullDay = listOf(low, high, secondLow)
        // 23:30 UTC on the 30th is well between the day's high and the next day's low.
        val trend = trendAt(Instant.parse("2026-08-30T23:30:00Z"), fullDay)
        assertEquals(TideTrend.FALLING, trend)
    }

    @Test
    fun `no events at all produces null tide fields - the one legitimate case for null`() {
        val result = TideTimeline.conditionsAt(emptyList(), listOf(Instant.parse("2026-08-30T12:00:00Z")), location, source()).single()
        assertNull(result.tideHeightFt)
        assertNull(result.tideTrend)
    }
}
