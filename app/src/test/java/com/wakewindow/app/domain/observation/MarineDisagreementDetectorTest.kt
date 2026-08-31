package com.wakewindow.app.domain.observation

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarineDisagreementDetectorTest {

    private val at = Instant.parse("2026-08-30T16:00:00Z")
    private val location = GeoPoint(28.408, -80.591)
    private fun source() = SourceReference("Test", null, at)

    private fun conditions(windKts: Double? = null, gustKts: Double? = null, waveFt: Double? = null, visNm: Double? = null, airTempF: Double? = null) =
        MarineConditions(
            timestamp = at, location = location,
            sustainedWindKts = windKts, gustKts = gustKts, waveHeightFt = waveFt, visibilityNm = visNm, airTemperatureF = airTempF,
            source = source(), confidence = Confidence.high(),
        )

    @Test
    fun `observed wind materially above forecast is detected`() {
        val forecast = conditions(windKts = 10.0)
        val observed = conditions(windKts = 19.0)
        val result = MarineDisagreementDetector.detect(forecast, observed)
        assertTrue(result.any { it.type == DisagreementType.WIND })
    }

    @Test
    fun `observed wave height materially above forecast is detected with a directional message`() {
        val forecast = conditions(waveFt = 2.0)
        val observed = conditions(waveFt = 4.5)
        val result = MarineDisagreementDetector.detect(forecast, observed)
        val wave = result.single { it.type == DisagreementType.WAVE_HEIGHT }
        assertTrue(wave.message.contains("higher"))
    }

    @Test
    fun `observed wave height materially below forecast is also detected`() {
        val forecast = conditions(waveFt = 5.0)
        val observed = conditions(waveFt = 1.0)
        val result = MarineDisagreementDetector.detect(forecast, observed)
        val wave = result.single { it.type == DisagreementType.WAVE_HEIGHT }
        assertTrue(wave.message.contains("lower"))
    }

    @Test
    fun `small harmless differences below threshold are not reported`() {
        val forecast = conditions(windKts = 10.0, waveFt = 2.0)
        val observed = conditions(windKts = 12.0, waveFt = 2.3)
        val result = MarineDisagreementDetector.detect(forecast, observed)
        assertEquals(0, result.size)
    }

    @Test
    fun `a field missing on either side never produces a fabricated disagreement`() {
        val forecast = conditions(windKts = 10.0, waveFt = null)
        val observed = conditions(windKts = 19.0, waveFt = 4.0)
        val result = MarineDisagreementDetector.detect(forecast, observed)
        assertEquals(0, result.count { it.type == DisagreementType.WAVE_HEIGHT })
        assertEquals(1, result.count { it.type == DisagreementType.WIND })
    }

    @Test
    fun `multiple simultaneous disagreements are all reported`() {
        val forecast = conditions(windKts = 10.0, waveFt = 2.0, visNm = 5.0)
        val observed = conditions(windKts = 20.0, waveFt = 5.0, visNm = 1.0)
        val result = MarineDisagreementDetector.detect(forecast, observed)
        assertEquals(3, result.size)
    }
}
