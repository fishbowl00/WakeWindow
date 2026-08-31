package com.wakewindow.app.domain.route

import com.wakewindow.app.domain.sun.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class QuickPlanPresetTest {

    private val zone = ZoneId.of("America/New_York")
    private val date = LocalDate.of(2026, 6, 21)

    @Test
    fun `every non-custom kind produces a window with the return after the departure`() {
        listOf(QuickPlanKind.MORNING, QuickPlanKind.AFTERNOON, QuickPlanKind.EVENING, QuickPlanKind.FULL_DAY).forEach { kind ->
            val window = QuickPlanPresets.windowFor(kind, date, zone, sunTimes = null)
            assertTrue("$kind: return ${window.returnTime} not after departure ${window.departure}", window.returnTime.isAfter(window.departure))
        }
    }

    @Test
    fun `CUSTOM has no computed window`() {
        try {
            QuickPlanPresets.windowFor(QuickPlanKind.CUSTOM, date, zone, sunTimes = null)
            org.junit.Assert.fail("expected an exception for CUSTOM")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `morning starts at the deterministic default when no sun times are available`() {
        val window = QuickPlanPresets.windowFor(QuickPlanKind.MORNING, date, zone, sunTimes = null)
        val expected = date.atTime(7, 0).atZone(zone).toInstant()
        assertEquals(expected, window.departure)
    }

    @Test
    fun `morning starts at real sunrise when sun times are available`() {
        val sunrise = date.atTime(5, 25).atZone(zone).toInstant()
        val sunTimes = SolarCalculator.SunTimes(date, sunrise, sunrise.plusSeconds(14 * 3600), null, null)
        val window = QuickPlanPresets.windowFor(QuickPlanKind.MORNING, date, zone, sunTimes)
        assertEquals(sunrise, window.departure)
    }

    @Test
    fun `full day spans morning start through late afternoon`() {
        val window = QuickPlanPresets.windowFor(QuickPlanKind.FULL_DAY, date, zone, sunTimes = null)
        assertEquals(date.atTime(7, 0).atZone(zone).toInstant(), window.departure)
        assertEquals(date.atTime(17, 0).atZone(zone).toInstant(), window.returnTime)
    }

    @Test
    fun `evening ends shortly after real sunset when available`() {
        val sunset = date.atTime(20, 30).atZone(zone).toInstant()
        val sunTimes = SolarCalculator.SunTimes(date, sunset.minusSeconds(14 * 3600), sunset, null, null)
        val window = QuickPlanPresets.windowFor(QuickPlanKind.EVENING, date, zone, sunTimes)
        assertEquals(sunset.plusSeconds(30 * 60), window.returnTime)
    }

    @Test
    fun `an unresolved sun time (polar edge case) never produces an invalid zero-duration window`() {
        val unresolved = SolarCalculator.SunTimes(date, null, null, null, null)
        listOf(QuickPlanKind.MORNING, QuickPlanKind.EVENING, QuickPlanKind.FULL_DAY).forEach { kind ->
            val window = QuickPlanPresets.windowFor(kind, date, zone, unresolved)
            assertTrue(window.returnTime.isAfter(window.departure))
        }
    }
}
