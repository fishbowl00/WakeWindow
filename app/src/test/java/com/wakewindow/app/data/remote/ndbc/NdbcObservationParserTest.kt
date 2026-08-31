package com.wakewindow.app.data.remote.ndbc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdbcObservationParserTest {

    private val header = "#STN       LAT      LON  YYYY MM DD hh mm WDIR WSPD   GST WVHT  DPD APD MWD   PRES  PTDY  ATMP  WTMP  DEWP  VIS   TIDE"
    private val units = "#text      deg      deg   yr mo day hr mn degT  m/s   m/s   m   sec sec degT   hPa   hPa  degC  degC  degC  nmi     ft"

    @Test
    fun `parses a well-formed row with all values present`() {
        val row = "41113    28.400  -80.533 2026 08 31 00 26  90   5.0   6.0  0.6   6  4.6  91 1015.0    MM  27.0  26.3  20.0   5.0     MM"
        val body = listOf(header, units, row).joinToString("\n")
        val parsed = NdbcObservationParser.parse(body)

        assertEquals(1, parsed.size)
        val r = parsed.single()
        assertEquals("41113", r.stationId)
        assertEquals(28.400, r.location.latitude, 0.001)
        assertEquals(-80.533, r.location.longitude, 0.001)
        assertEquals(0.6, r.waveHeightM!!, 0.001)
        assertEquals(5.0, r.windSpeedMps!!, 0.001)
    }

    @Test
    fun `MM missing-value markers become null, never zero`() {
        val row = "41009    28.508  -80.185 2026 08 31 00 30  50   3.0   4.0   MM  MM   MM  MM 1017.4    MM  28.3    MM  25.3   MM     MM"
        val parsed = NdbcObservationParser.parse(listOf(header, units, row).joinToString("\n"))
        val r = parsed.single()
        assertNull("wave height should be null, not 0.0, when NDBC reports MM", r.waveHeightM)
        assertNull(r.waterTempC)
        assertNull(r.visibilityNm)
        assertEquals(3.0, r.windSpeedMps!!, 0.001) // a present field still parses normally
    }

    @Test
    fun `a station with only wind data has hasWindData true and hasWaveData false`() {
        val row = "41009    28.508  -80.185 2026 08 31 00 30  50   3.0   4.0   MM  MM   MM  MM 1017.4    MM  28.3    MM  25.3   MM     MM"
        val r = NdbcObservationParser.parse(listOf(header, units, row).joinToString("\n")).single()
        assertTrue(r.hasWindData)
        assertTrue(!r.hasWaveData)
    }

    @Test
    fun `a pure wave buoy with no wind sensor has hasWaveData true and hasWindData false`() {
        val row = "41113    28.400  -80.533 2026 08 31 00 26  MM    MM    MM  0.6   6  4.6  91     MM    MM    MM  26.3    MM   MM     MM"
        val r = NdbcObservationParser.parse(listOf(header, units, row).joinToString("\n")).single()
        assertTrue(!r.hasWindData)
        assertTrue(r.hasWaveData)
    }

    @Test
    fun `a malformed short row is skipped rather than crashing the whole parse`() {
        val goodRow = "41009    28.508  -80.185 2026 08 31 00 30  50   3.0   4.0   MM  MM   MM  MM 1017.4    MM  28.3    MM  25.3   MM     MM"
        val truncatedRow = "41010    28.860  -78.478 2026 08 31"
        val body = listOf(header, units, truncatedRow, goodRow).joinToString("\n")
        val parsed = NdbcObservationParser.parse(body)
        assertEquals(1, parsed.size)
        assertEquals("41009", parsed.single().stationId)
    }

    @Test
    fun `a row with a non-numeric latitude is skipped rather than crashing`() {
        val badRow = "BADID    notalat  -80.185 2026 08 31 00 30  50   3.0   4.0   MM  MM   MM  MM 1017.4    MM  28.3    MM  25.3   MM     MM"
        val goodRow = "41009    28.508  -80.185 2026 08 31 00 30  50   3.0   4.0   MM  MM   MM  MM 1017.4    MM  28.3    MM  25.3   MM     MM"
        val parsed = NdbcObservationParser.parse(listOf(header, units, badRow, goodRow).joinToString("\n"))
        assertEquals(1, parsed.size)
    }

    @Test
    fun `header and blank lines are never parsed as data rows`() {
        val parsed = NdbcObservationParser.parse(listOf(header, units, "", "   ").joinToString("\n"))
        assertEquals(0, parsed.size)
    }

    @Test
    fun `observedAt is parsed as the correct UTC instant`() {
        val row = "41009    28.508  -80.185 2026 08 31 00 30  50   3.0   4.0   MM  MM   MM  MM 1017.4    MM  28.3    MM  25.3   MM     MM"
        val r = NdbcObservationParser.parse(listOf(header, units, row).joinToString("\n")).single()
        assertEquals(java.time.Instant.parse("2026-08-31T00:30:00Z"), r.observedAt)
    }
}
