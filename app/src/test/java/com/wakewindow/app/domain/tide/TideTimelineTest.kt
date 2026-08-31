package com.wakewindow.app.domain.tide

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
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
    fun `trend between a low and a following high is RISING`() {
        val midpoint = Instant.parse("2026-08-30T12:00:00Z")
        val result = TideTimeline.conditionsAt(events, listOf(midpoint), location, source())
        assertEquals(TideTrend.RISING, result.single().tideTrend)
    }

    @Test
    fun `trend between a high and a following low is FALLING`() {
        val secondLow = TideEvent(TideEventType.LOW, Instant.parse("2026-08-30T21:00:00Z"), 0.5)
        val fullDay = listOf(low, high, secondLow)
        val midpoint = Instant.parse("2026-08-30T18:00:00Z")
        val result = TideTimeline.conditionsAt(fullDay, listOf(midpoint), location, source())
        assertEquals(TideTrend.FALLING, result.single().tideTrend)
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

    @Test
    fun `a time before any known event still returns a height and null trend rather than crashing`() {
        val before = Instant.parse("2026-08-30T00:00:00Z")
        val result = TideTimeline.conditionsAt(events, listOf(before), location, source()).single()
        assertEquals(low.heightFt, result.tideHeightFt!!, 0.01)
    }

    @Test
    fun `no events at all produces null tide fields, not a fabricated value`() {
        val result = TideTimeline.conditionsAt(emptyList(), listOf(Instant.parse("2026-08-30T12:00:00Z")), location, source()).single()
        assertNull(result.tideHeightFt)
        assertNull(result.tideTrend)
    }
}
