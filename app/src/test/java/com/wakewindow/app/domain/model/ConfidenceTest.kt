package com.wakewindow.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceTest {

    @Test
    fun `worstOf picks the lower confidence level`() {
        val high = Confidence.high()
        val low = Confidence(ConfidenceLevel.LOW, listOf("sparse data"))
        assertEquals(ConfidenceLevel.LOW, high.worstOf(low).level)
        assertEquals(ConfidenceLevel.LOW, low.worstOf(high).level)
    }

    @Test
    fun `worstOf merges reasons from both sides without duplicates`() {
        val a = Confidence(ConfidenceLevel.MEDIUM, listOf("reason A"))
        val b = Confidence(ConfidenceLevel.LOW, listOf("reason B", "reason A"))
        val result = a.worstOf(b)
        assertEquals(2, result.reasons.size)
        assertTrue(result.reasons.containsAll(listOf("reason A", "reason B")))
    }

    @Test
    fun `unavailable beats every other level`() {
        val unavailable = Confidence.unavailable("no data")
        val high = Confidence.high()
        assertEquals(ConfidenceLevel.UNAVAILABLE, unavailable.worstOf(high).level)
    }
}
