package com.wakewindow.app.data.remote.coops

import com.wakewindow.app.domain.tide.CurrentEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Covers mapping of a real NOAA CO-OPS `currents_predictions` (`interval=MAX_SLACK`) response
 * shape - verified live against station FPI0901 on 2026-08-30 (see docs/DATA_SOURCES.md
 * "Current predictions"). `Velocity_Major` is signed in the raw response; the domain
 * [com.wakewindow.app.domain.tide.CurrentEvent.speedKts] is always a magnitude.
 */
class CoopsCurrentMapperTest {

    private fun dto(time: String, type: String, velocityMajor: Double, floodDir: Double? = 258.0, ebbDir: Double? = 81.0) =
        CoopsCurrentEventDto(time = time, type = type, velocityMajor = velocityMajor, meanFloodDir = floodDir, meanEbbDir = ebbDir)

    @Test
    fun `a flood event maps to FLOOD_MAX with a positive magnitude and the flood direction`() {
        val response = CoopsCurrentPredictionsResponse(
            CoopsCurrentPredictionsBody(listOf(dto("2026-08-30 00:56", "flood", 3.14))),
        )
        val event = CoopsCurrentMapper.mapEvents(response).single()
        assertEquals(CurrentEventType.FLOOD_MAX, event.type)
        assertEquals(3.14, event.speedKts, 0.001)
        assertEquals(258.0, event.directionDeg)
        assertEquals(Instant.parse("2026-08-30T00:56:00Z"), event.time)
    }

    @Test
    fun `an ebb event maps to EBB_MAX with a positive magnitude despite a negative raw velocity, and the ebb direction`() {
        val response = CoopsCurrentPredictionsResponse(
            CoopsCurrentPredictionsBody(listOf(dto("2026-08-30 06:51", "ebb", -3.51))),
        )
        val event = CoopsCurrentMapper.mapEvents(response).single()
        assertEquals(CurrentEventType.EBB_MAX, event.type)
        assertEquals(3.51, event.speedKts, 0.001)
        assertEquals(81.0, event.directionDeg)
    }

    @Test
    fun `a slack event maps to SLACK with zero speed and no direction - direction is undefined at zero velocity`() {
        val response = CoopsCurrentPredictionsResponse(
            CoopsCurrentPredictionsBody(listOf(dto("2026-08-30 04:05", "slack", 0.0))),
        )
        val event = CoopsCurrentMapper.mapEvents(response).single()
        assertEquals(CurrentEventType.SLACK, event.type)
        assertEquals(0.0, event.speedKts, 0.001)
        assertNull(event.directionDeg)
    }

    @Test
    fun `a full flood-slack-ebb-slack cycle maps in order`() {
        val response = CoopsCurrentPredictionsResponse(
            CoopsCurrentPredictionsBody(
                listOf(
                    dto("2026-08-30 00:56", "flood", 3.14),
                    dto("2026-08-30 04:05", "slack", 0.0),
                    dto("2026-08-30 06:51", "ebb", -3.51),
                    dto("2026-08-30 10:37", "slack", -0.0),
                ),
            ),
        )
        val events = CoopsCurrentMapper.mapEvents(response)
        assertEquals(listOf(CurrentEventType.FLOOD_MAX, CurrentEventType.SLACK, CurrentEventType.EBB_MAX, CurrentEventType.SLACK), events.map { it.type })
    }

    @Test
    fun `an unrecognized type is dropped rather than guessed at`() {
        val response = CoopsCurrentPredictionsResponse(
            CoopsCurrentPredictionsBody(listOf(dto("2026-08-30 00:56", "unknown-phase", 1.0))),
        )
        assertTrue(CoopsCurrentMapper.mapEvents(response).isEmpty())
    }

    @Test
    fun `an unparseable time drops the row rather than crashing`() {
        val response = CoopsCurrentPredictionsResponse(
            CoopsCurrentPredictionsBody(listOf(dto("not-a-time", "flood", 3.14))),
        )
        assertTrue(CoopsCurrentMapper.mapEvents(response).isEmpty())
    }

    @Test
    fun `a null current_predictions body maps to an empty list, never a crash`() {
        assertTrue(CoopsCurrentMapper.mapEvents(CoopsCurrentPredictionsResponse(null)).isEmpty())
    }
}
