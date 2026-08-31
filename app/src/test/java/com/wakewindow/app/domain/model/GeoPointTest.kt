package com.wakewindow.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoPointTest {

    @Test
    fun `distance between identical points is zero`() {
        val point = GeoPoint(28.408, -80.591)
        assertEquals(0.0, point.distanceNmTo(point), 0.001)
    }

    @Test
    fun `distance between Port Canaveral and known offshore point is plausible`() {
        // 28.408,-80.591 (port) to 28.30,-80.30 (~offshore) is roughly 18-20 NM.
        val port = GeoPoint(28.408, -80.591)
        val offshore = GeoPoint(28.30, -80.30)
        val distance = port.distanceNmTo(offshore)
        assertEquals(19.0, distance, 3.0)
    }

    @Test
    fun `interpolateTo at fraction 0 returns the start point`() {
        val a = GeoPoint(28.0, -80.0)
        val b = GeoPoint(29.0, -81.0)
        val result = a.interpolateTo(b, 0.0)
        assertEquals(a.latitude, result.latitude, 0.0001)
        assertEquals(a.longitude, result.longitude, 0.0001)
    }

    @Test
    fun `interpolateTo at fraction 1 returns the end point`() {
        val a = GeoPoint(28.0, -80.0)
        val b = GeoPoint(29.0, -81.0)
        val result = a.interpolateTo(b, 1.0)
        assertEquals(b.latitude, result.latitude, 0.0001)
        assertEquals(b.longitude, result.longitude, 0.0001)
    }

    @Test
    fun `latitude out of range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(91.0, 0.0) }
    }

    @Test
    fun `longitude out of range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(0.0, 181.0) }
    }
}
