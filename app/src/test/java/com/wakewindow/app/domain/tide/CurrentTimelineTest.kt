package com.wakewindow.app.domain.tide

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CurrentTimelineTest {

    private val location = GeoPoint(28.408, -80.591)
    private fun source(at: Instant) = SourceReference("NOAA Tides & Currents", null, at)

    private val floodMax = CurrentEvent(CurrentEventType.FLOOD_MAX, Instant.parse("2026-08-30T00:56:00Z"), 3.14, 258.0)
    private val slack1 = CurrentEvent(CurrentEventType.SLACK, Instant.parse("2026-08-30T04:05:00Z"), 0.0, null)
    private val ebbMax = CurrentEvent(CurrentEventType.EBB_MAX, Instant.parse("2026-08-30T06:51:00Z"), 3.51, 81.0)

    @Test
    fun `an hour exactly at a flood-max event returns that event's speed and direction`() {
        val result = CurrentTimeline.conditionsAt(listOf(floodMax, slack1, ebbMax), listOf(floodMax.time), location, source(floodMax.time))
        assertEquals(3.14, result.single().currentSpeedKts!!, 0.001)
        assertEquals(258.0, result.single().currentDirectionDeg)
    }

    @Test
    fun `an hour between flood-max and slack shows a smoothly decreasing speed, still flood direction`() {
        val midpoint = floodMax.time.plusSeconds(java.time.Duration.between(floodMax.time, slack1.time).seconds / 2)
        val result = CurrentTimeline.conditionsAt(listOf(floodMax, slack1, ebbMax), listOf(midpoint), location, source(midpoint)).single()
        assertTrue("expected speed between 0 and flood max, got ${result.currentSpeedKts}", result.currentSpeedKts!! in 0.0..floodMax.speedKts)
        assertEquals(258.0, result.currentDirectionDeg)
    }

    @Test
    fun `an hour between slack and ebb-max reports the ebb direction`() {
        val midpoint = slack1.time.plusSeconds(java.time.Duration.between(slack1.time, ebbMax.time).seconds / 2)
        val result = CurrentTimeline.conditionsAt(listOf(floodMax, slack1, ebbMax), listOf(midpoint), location, source(midpoint)).single()
        assertEquals(81.0, result.currentDirectionDeg)
        assertTrue(result.currentSpeedKts!! > 0.0)
    }

    @Test
    fun `an hour before the first known event falls back to that event's values`() {
        val before = floodMax.time.minusSeconds(3600)
        val result = CurrentTimeline.conditionsAt(listOf(floodMax, slack1, ebbMax), listOf(before), location, source(before)).single()
        assertEquals(floodMax.speedKts, result.currentSpeedKts!!, 0.001)
        assertEquals(floodMax.directionDeg, result.currentDirectionDeg)
    }

    @Test
    fun `an hour after the last known event falls back to that event's values`() {
        val after = ebbMax.time.plusSeconds(3600)
        val result = CurrentTimeline.conditionsAt(listOf(floodMax, slack1, ebbMax), listOf(after), location, source(after)).single()
        assertEquals(ebbMax.speedKts, result.currentSpeedKts!!, 0.001)
        assertEquals(ebbMax.directionDeg, result.currentDirectionDeg)
    }

    @Test
    fun `no events at all produces null speed and direction, never a fabricated calm reading`() {
        val at = Instant.parse("2026-08-30T12:00:00Z")
        val result = CurrentTimeline.conditionsAt(emptyList(), listOf(at), location, source(at)).single()
        assertNull(result.currentSpeedKts)
        assertNull(result.currentDirectionDeg)
        assertNull(result.nextCurrentEvent)
    }

    @Test
    fun `nextCurrentEvent points to the next upcoming turn after the queried hour`() {
        val at = floodMax.time.plusSeconds(60)
        val result = CurrentTimeline.conditionsAt(listOf(floodMax, slack1, ebbMax), listOf(at), location, source(at)).single()
        assertEquals(slack1, result.nextCurrentEvent)
    }
}
