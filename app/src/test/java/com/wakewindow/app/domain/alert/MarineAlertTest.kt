package com.wakewindow.app.domain.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarineAlertTest {

    private fun alert(effective: Instant?, expires: Instant?) = MarineAlert(
        id = "x", event = "Small Craft Advisory", headline = null,
        severity = MarineAlertSeverity.ADVISORY, effective = effective, expires = expires, areaDescription = null,
        impact = MarineAlertImpact(AlertImpactCategory.MARINE_NAVIGATION, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.CAUTION),
    )

    @Test
    fun `an alert that expired before the outing does not overlap it`() {
        val a = alert(Instant.parse("2026-08-30T06:00:00Z"), Instant.parse("2026-08-30T10:00:00Z"))
        val outingStart = Instant.parse("2026-08-30T14:00:00Z")
        val outingEnd = Instant.parse("2026-08-30T20:00:00Z")
        assertFalse(a.overlaps(outingStart, outingEnd))
    }

    @Test
    fun `a future alert that starts during the outing does overlap it`() {
        val a = alert(Instant.parse("2026-08-30T17:00:00Z"), Instant.parse("2026-08-30T22:00:00Z"))
        val outingStart = Instant.parse("2026-08-30T12:00:00Z")
        val outingEnd = Instant.parse("2026-08-30T20:00:00Z")
        assertTrue(a.overlaps(outingStart, outingEnd))
    }

    @Test
    fun `an alert entirely after the outing does not overlap it`() {
        val a = alert(Instant.parse("2026-08-31T02:00:00Z"), Instant.parse("2026-08-31T08:00:00Z"))
        val outingStart = Instant.parse("2026-08-30T12:00:00Z")
        val outingEnd = Instant.parse("2026-08-30T20:00:00Z")
        assertFalse(a.overlaps(outingStart, outingEnd))
    }

    @Test
    fun `timingRelativeTo reports ACTIVE_NOW when the alert covers the current instant`() {
        val a = alert(Instant.parse("2026-08-30T10:00:00Z"), Instant.parse("2026-08-30T20:00:00Z"))
        val now = Instant.parse("2026-08-30T15:00:00Z")
        assertEquals(AlertTiming.ACTIVE_NOW, a.timingRelativeTo(now, now, now.plusSeconds(3600)))
    }

    @Test
    fun `timingRelativeTo reports STARTS_DURING_OUTING for a future alert overlapping the plan`() {
        val a = alert(Instant.parse("2026-08-30T17:00:00Z"), Instant.parse("2026-08-30T22:00:00Z"))
        val now = Instant.parse("2026-08-30T09:00:00Z")
        val outingStart = Instant.parse("2026-08-30T12:00:00Z")
        val outingEnd = Instant.parse("2026-08-30T20:00:00Z")
        assertEquals(AlertTiming.STARTS_DURING_OUTING, a.timingRelativeTo(now, outingStart, outingEnd))
    }

    @Test
    fun `timingRelativeTo reports ALREADY_EXPIRED for a past alert`() {
        val a = alert(Instant.parse("2026-08-29T10:00:00Z"), Instant.parse("2026-08-29T20:00:00Z"))
        val now = Instant.parse("2026-08-30T09:00:00Z")
        assertEquals(AlertTiming.ALREADY_EXPIRED, a.timingRelativeTo(now, now, now.plusSeconds(3600)))
    }

    @Test
    fun `an alert with unknown onset and expiry is treated as active - fail-unsafe`() {
        val a = alert(null, null)
        assertTrue(a.isActiveAt(Instant.now()))
        assertTrue(a.overlaps(Instant.now(), Instant.now().plusSeconds(3600)))
    }
}
