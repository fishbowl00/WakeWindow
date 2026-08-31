package com.wakewindow.app.domain.sun

import com.wakewindow.app.domain.model.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

/**
 * These assert *qualitative, deterministic* properties of the sunrise/sunset approximation
 * (order, seasonal direction, rough day length) rather than exact-minute values against a
 * live reference - this session's environment has no outbound network access to verify against
 * a real almanac (see the sprint report's network-access caveat). The algorithm itself
 * (Almanac for Computers, 1990) is a well-established closed-form approximation - see
 * [SolarCalculator]'s class doc.
 */
class SolarCalculatorTest {

    private val portCanaveral = GeoPoint(28.408, -80.591)
    private val boston = GeoPoint(42.36, -71.06)
    private val equator = GeoPoint(0.0, -80.0)
    private val farNorth = GeoPoint(70.0, 25.0) // above the Arctic Circle

    @Test
    fun `sunrise happens before sunset on the same day`() {
        val times = SolarCalculator.calculate(portCanaveral, LocalDate.of(2026, 6, 21))
        assertTrue(times.sunrise != null && times.sunset != null)
        assertTrue(times.sunrise!!.isBefore(times.sunset))
    }

    @Test
    fun `civil twilight begins before sunrise and ends after sunset`() {
        val times = SolarCalculator.calculate(portCanaveral, LocalDate.of(2026, 3, 20))
        assertTrue(times.civilTwilightBegin!!.isBefore(times.sunrise))
        assertTrue(times.civilTwilightEnd!!.isAfter(times.sunset))
    }

    @Test
    fun `a mid-latitude location has a longer day in June than in December`() {
        val summer = SolarCalculator.calculate(boston, LocalDate.of(2026, 6, 21))
        val winter = SolarCalculator.calculate(boston, LocalDate.of(2026, 12, 21))
        val summerLength = Duration.between(summer.sunrise, summer.sunset)
        val winterLength = Duration.between(winter.sunrise, winter.sunset)
        assertTrue(summerLength > winterLength)
    }

    @Test
    fun `day length near the equator stays close to twelve hours year-round`() {
        listOf(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21)).forEach { date ->
            val times = SolarCalculator.calculate(equator, date)
            val length = Duration.between(times.sunrise, times.sunset)
            assertTrue("day length at equator on $date was $length", length.toMinutes() in 700..740)
        }
    }

    @Test
    fun `a far-north location has no sunrise in deep winter - polar night, not a guess`() {
        val times = SolarCalculator.calculate(farNorth, LocalDate.of(2026, 12, 21))
        assertTrue(times.isPolarDayOrNight)
        assertTrue(times.sunrise == null)
    }

    @Test
    fun `an exact-pole coordinate honestly reports unresolved rather than a garbage instant`() {
        val times = SolarCalculator.calculate(GeoPoint(90.0, 0.0), LocalDate.of(2026, 6, 21))
        assertTrue(times.isPolarDayOrNight)
        assertTrue(times.sunrise == null && times.sunset == null)
    }

    @Test
    fun `the same location and date always produces the same result - deterministic`() {
        val date = LocalDate.of(2026, 8, 31)
        val first = SolarCalculator.calculate(portCanaveral, date)
        val second = SolarCalculator.calculate(portCanaveral, date)
        assertTrue(first.sunrise == second.sunrise)
        assertTrue(first.sunset == second.sunset)
    }
}
