package com.wakewindow.app.data.remote.nws

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NwsMapperTest {

    private val location = GeoPoint(28.30, -80.30)
    private fun source(at: Instant) = SourceReference("National Weather Service", null, at)

    @Test
    fun `parseNwsInstant handles a numeric zone offset that Instant_parse itself rejects`() {
        val instant = NwsMapper.parseNwsInstant("2026-08-31T07:00:16-04:00")
        // 07:00:16 -04:00 is 11:00:16 UTC.
        assertEquals(Instant.parse("2026-08-31T11:00:16Z"), instant)
    }

    @Test
    fun `a multi-hour grid interval value applies to every hour it spans`() {
        // A real NWS waveHeight value observed live: "2026-08-30T18:00:00+00:00/PT6H" - one
        // value covering six hours, not a per-hour array like /forecast/hourly.
        val properties = NwsGridpointsProperties(
            waveHeight = NwsGridQuantitative(
                uom = "wmoUnit:m",
                values = listOf(NwsGridValue(validTime = "2026-08-30T18:00:00+00:00/PT6H", value = 0.9144)),
            ),
        )
        val hours = listOf(
            Instant.parse("2026-08-30T18:00:00Z"),
            Instant.parse("2026-08-30T20:00:00Z"),
            Instant.parse("2026-08-30T23:00:00Z"),
        )
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, hours, location, source(hours.first()))

        // 0.9144 m = 3.0 ft, applied to every hour within the 6-hour span.
        result.forEach { assertEquals(3.0, it.waveHeightFt!!, 0.01) }
    }

    @Test
    fun `an hour with no covering interval leaves the field null rather than reusing a stale value`() {
        val properties = NwsGridpointsProperties(
            waveHeight = NwsGridQuantitative(
                uom = "wmoUnit:m",
                values = listOf(NwsGridValue(validTime = "2026-08-30T18:00:00+00:00/PT1H", value = 1.0)),
            ),
        )
        val hourOutsideCoverage = Instant.parse("2026-08-31T04:00:00Z")
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, listOf(hourOutsideCoverage), location, source(hourOutsideCoverage))
        assertNull(result.single().waveHeightFt)
    }

    @Test
    fun `wind speed and gust are converted from km-h to knots`() {
        val hour = Instant.parse("2026-08-30T17:00:00Z")
        val properties = NwsGridpointsProperties(
            windSpeed = NwsGridQuantitative(values = listOf(NwsGridValue("2026-08-30T17:00:00+00:00/PT1H", 18.52))), // ~10 kt
        )
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, listOf(hour), location, source(hour)).single()
        assertEquals(10.0, result.sustainedWindKts!!, 0.5)
    }

    @Test
    fun `thunderstorm probability passes through as a raw percent with no unit conversion`() {
        val hour = Instant.parse("2026-08-30T17:00:00Z")
        val properties = NwsGridpointsProperties(
            probabilityOfThunder = NwsGridQuantitative(values = listOf(NwsGridValue("2026-08-30T17:00:00+00:00/PT1H", 40.0))),
        )
        val result = NwsMapper.mapGridpointsToMarineConditions(properties, listOf(hour), location, source(hour)).single()
        assertEquals(40, result.thunderstormProbabilityPercent)
    }

    @Test
    fun `classifySeverity maps extreme, severe, and advisory marine events correctly`() {
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME, NwsMapper.classifySeverity("Special Marine Warning"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME, NwsMapper.classifySeverity("Hurricane Warning"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.SEVERE, NwsMapper.classifySeverity("Gale Warning"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.ADVISORY, NwsMapper.classifySeverity("Small Craft Advisory"))
        assertEquals(com.wakewindow.app.domain.alert.MarineAlertSeverity.WATCH, NwsMapper.classifySeverity("Coastal Flood Watch"))
    }

    @Test
    fun `an alert with no onset or expiry is treated as active (fail-unsafe, not fail-open)`() {
        val alert = com.wakewindow.app.domain.alert.MarineAlert(
            id = "x", event = "Special Marine Warning", headline = null,
            severity = com.wakewindow.app.domain.alert.MarineAlertSeverity.EXTREME,
            effective = null, expires = null, areaDescription = null,
        )
        assertTrue(alert.isActiveAt(Instant.now()))
    }
}
