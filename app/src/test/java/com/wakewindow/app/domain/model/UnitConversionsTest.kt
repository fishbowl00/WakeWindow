package com.wakewindow.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConversionsTest {

    @Test
    fun `10 meters-per-second is about 19_4 knots`() {
        assertEquals(19.44, UnitConversions.mpsToKnots(10.0), 0.01)
    }

    @Test
    fun `1 meter is about 3_28 feet`() {
        assertEquals(3.28, UnitConversions.metersToFeet(1.0), 0.01)
    }

    @Test
    fun `0 celsius is 32 fahrenheit`() {
        assertEquals(32.0, UnitConversions.celsiusToFahrenheit(0.0), 0.001)
    }

    @Test
    fun `100 celsius is 212 fahrenheit`() {
        assertEquals(212.0, UnitConversions.celsiusToFahrenheit(100.0), 0.001)
    }

    @Test
    fun `1 statute mile is less than 1 nautical mile`() {
        val nm = UnitConversions.milesToNauticalMiles(1.0)
        assertEquals(0.869, nm, 0.01)
        org.junit.Assert.assertTrue(nm < 1.0)
    }
}
