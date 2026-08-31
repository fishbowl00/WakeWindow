package com.wakewindow.app.data.remote.ndbc

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.observation.ObservationFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NdbcStationSelectorTest {

    private val queryLocation = GeoPoint(28.408, -80.591)
    private val now = Instant.parse("2026-08-31T00:52:00Z")

    private fun row(
        id: String,
        location: GeoPoint,
        observedAt: Instant = now,
        windSpeedMps: Double? = 5.0,
        waveHeightM: Double? = 1.0,
    ) = NdbcObservationRow(
        stationId = id,
        location = location,
        observedAt = observedAt,
        windDirectionDeg = null,
        windSpeedMps = windSpeedMps,
        gustMps = null,
        waveHeightM = waveHeightM,
        dominantWavePeriodSec = null,
        averageWavePeriodSec = null,
        waveDirectionDeg = null,
        pressureHpa = null,
        airTempC = null,
        waterTempC = null,
        dewpointC = null,
        visibilityNm = null,
    )

    @Test
    fun `selects the closer of two equally fresh, equally capable stations`() {
        val near = row("NEAR", GeoPoint(28.42, -80.58))
        val far = row("FAR", GeoPoint(28.9, -78.5))
        val selected = NdbcStationSelector.select(listOf(near, far), queryLocation, now)!!
        assertEquals("NEAR", selected.row.stationId)
    }

    @Test
    fun `a fresher farther station beats a stale nearer one`() {
        val staleNear = row("STALE_NEAR", GeoPoint(28.42, -80.58), observedAt = now.minusSeconds(3 * 3600))
        val freshFar = row("FRESH_FAR", GeoPoint(28.7, -80.3), observedAt = now.minusSeconds(10 * 60))
        val selected = NdbcStationSelector.select(listOf(staleNear, freshFar), queryLocation, now)!!
        assertEquals("FRESH_FAR", selected.row.stationId)
    }

    @Test
    fun `a station with both wind and wave data beats an equally fresh, equally close one with only wind`() {
        val windOnly = row("WIND_ONLY", GeoPoint(28.42, -80.58), waveHeightM = null)
        val both = row("BOTH", GeoPoint(28.43, -80.58), windSpeedMps = 5.0, waveHeightM = 1.0)
        val selected = NdbcStationSelector.select(listOf(windOnly, both), queryLocation, now)!!
        assertEquals("BOTH", selected.row.stationId)
    }

    @Test
    fun `a station beyond the useful distance is never selected, even if it's the only candidate`() {
        val tooFar = row("TOO_FAR", GeoPoint(31.0, -75.0)) // several hundred NM away
        assertNull(NdbcStationSelector.select(listOf(tooFar), queryLocation, now))
    }

    @Test
    fun `a station with neither wind nor wave data is never selected`() {
        val empty = row("EMPTY", GeoPoint(28.42, -80.58), windSpeedMps = null, waveHeightM = null)
        assertNull(NdbcStationSelector.select(listOf(empty), queryLocation, now))
    }

    @Test
    fun `an observation reported in the future (clock skew) is not trusted`() {
        val future = row("FUTURE", GeoPoint(28.42, -80.58), observedAt = now.plusSeconds(3600))
        assertNull(NdbcStationSelector.select(listOf(future), queryLocation, now))
    }

    @Test
    fun `freshness classification matches the documented thresholds`() {
        assertEquals(ObservationFreshness.FRESH, ObservationFreshness.fromAge(java.time.Duration.ofMinutes(10)))
        assertEquals(ObservationFreshness.AGING, ObservationFreshness.fromAge(java.time.Duration.ofMinutes(60)))
        assertEquals(ObservationFreshness.STALE, ObservationFreshness.fromAge(java.time.Duration.ofMinutes(120)))
        assertEquals(ObservationFreshness.UNUSABLE, ObservationFreshness.fromAge(java.time.Duration.ofMinutes(200)))
    }

    @Test
    fun `distance is computed correctly for the selected station`() {
        val near = row("NEAR", GeoPoint(28.42, -80.58))
        val selected = NdbcStationSelector.select(listOf(near), queryLocation, now)!!
        assertTrue(selected.distanceNm < 5.0)
    }
}
