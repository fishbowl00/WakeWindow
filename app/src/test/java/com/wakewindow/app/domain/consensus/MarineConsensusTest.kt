package com.wakewindow.app.domain.consensus

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarineConsensusTest {

    private val at = Instant.parse("2026-08-30T16:00:00Z")
    private val location = GeoPoint(28.408, -80.591)

    private fun reading(sourceName: String, windKts: Double? = null, thunderPercent: Int? = null, windDirDeg: Double? = null, visNm: Double? = null) =
        MarineConditions(
            timestamp = at,
            location = location,
            sustainedWindKts = windKts,
            windDirectionDeg = windDirDeg,
            thunderstormProbabilityPercent = thunderPercent,
            visibilityNm = visNm,
            source = SourceReference(sourceName, null, at),
            confidence = Confidence.high(),
        )

    @Test
    fun `empty readings produce no merged result`() {
        assertNull(MarineConsensus.merge(emptyList()))
    }

    @Test
    fun `a single reading passes through with reduced confidence`() {
        val merged = MarineConsensus.merge(listOf(reading("NWS", windKts = 10.0)))!!
        assertEquals(10.0, merged.sustainedWindKts!!, 0.001)
        assertEquals(ConfidenceLevel.MEDIUM, merged.confidence.level)
    }

    @Test
    fun `numeric fields average across providers`() {
        val merged = MarineConsensus.merge(listOf(reading("NWS", windKts = 10.0), reading("Open-Meteo", windKts = 20.0)))!!
        assertEquals(15.0, merged.sustainedWindKts!!, 0.001)
        assertEquals(ConfidenceLevel.HIGH, merged.confidence.level)
    }

    @Test
    fun `thunderstorm probability takes the worse (higher) reading, never diluted by a calmer source`() {
        val merged = MarineConsensus.merge(listOf(reading("NWS", thunderPercent = 20), reading("Open-Meteo", thunderPercent = 70)))!!
        assertEquals(70, merged.thunderstormProbabilityPercent)
    }

    @Test
    fun `visibility takes the worse (lower) reading`() {
        val merged = MarineConsensus.merge(listOf(reading("NWS", visNm = 5.0), reading("Open-Meteo", visNm = 1.0)))!!
        assertEquals(1.0, merged.visibilityNm!!, 0.001)
    }

    @Test
    fun `wind direction near the 0-360 wraparound averages correctly via circular mean`() {
        // 350 degrees and 10 degrees should average to 0 (360), not 180 (naive average).
        val merged = MarineConsensus.merge(
            listOf(
                reading("NWS", windKts = 5.0, windDirDeg = 350.0),
                reading("Open-Meteo", windKts = 5.0, windDirDeg = 10.0),
            ),
        )!!
        val direction = merged.windDirectionDeg!!
        assertTrue("expected direction near 0/360, got $direction", direction < 20.0 || direction > 340.0)
    }

    @Test
    fun `marine alerts from multiple sources are unioned, not dropped`() {
        val alert1 = com.wakewindow.app.domain.alert.MarineAlert(
            id = "a1", event = "Small Craft Advisory", headline = null,
            severity = com.wakewindow.app.domain.alert.MarineAlertSeverity.ADVISORY,
            effective = null, expires = null, areaDescription = null,
        )
        val r1 = reading("NWS").copy(marineAlerts = listOf(alert1))
        val r2 = reading("Open-Meteo")
        val merged = MarineConsensus.merge(listOf(r1, r2))!!
        assertEquals(1, merged.marineAlerts.size)
    }
}
