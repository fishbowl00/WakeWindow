package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.observation.WaterEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Covers docs/MARINE_SCORING.md "Environment-aware evidence requirements": missing wave data
 * is a real evidence gap at a coastal/offshore launch (there are real waves not being
 * reported) but not at an inland lake (there are no waves to report), so the same missing
 * field must not be treated identically at both.
 */
class EvidenceRequirementEvaluatorTest {

    private val at: Instant = Instant.parse("2026-08-30T16:00:00Z")
    private val location = GeoPoint(28.408, -80.591)

    private fun conditions(waveHeightFt: Double?) = MarineConditions(
        timestamp = at,
        location = location,
        waveHeightFt = waveHeightFt,
        source = SourceReference("Test", null, at),
        confidence = Confidence.high(),
    )

    @Test
    fun `a coastal launch with no wave data is ceilinged at GOOD`() {
        val result = EvidenceRequirementEvaluator.evaluate(WaterEnvironment.NEARSHORE, conditions(waveHeightFt = null))
        assertEquals(BoatingCategory.GOOD, result?.first)
    }

    @Test
    fun `an offshore launch with no wave data is also ceilinged`() {
        val result = EvidenceRequirementEvaluator.evaluate(WaterEnvironment.OFFSHORE, conditions(waveHeightFt = null))
        assertEquals(BoatingCategory.GOOD, result?.first)
    }

    @Test
    fun `a coastal launch WITH wave data is never ceilinged`() {
        val result = EvidenceRequirementEvaluator.evaluate(WaterEnvironment.NEARSHORE, conditions(waveHeightFt = 1.0))
        assertNull(result)
    }

    @Test
    fun `an inland launch with no wave data is never ceilinged - there is no wave data to be missing`() {
        val result = EvidenceRequirementEvaluator.evaluate(WaterEnvironment.INLAND, conditions(waveHeightFt = null))
        assertNull(result)
    }

    @Test
    fun `an unknown environment is never ceilinged - guessing would be worse than not gating`() {
        val result = EvidenceRequirementEvaluator.evaluate(WaterEnvironment.UNKNOWN, conditions(waveHeightFt = null))
        assertNull(result)
    }

    @Test
    fun `a harbor launch with no wave data is ceilinged - the coastal cluster all requires it`() {
        val result = EvidenceRequirementEvaluator.evaluate(WaterEnvironment.HARBOR, conditions(waveHeightFt = null))
        assertEquals(BoatingCategory.GOOD, result?.first)
    }
}
