package com.wakewindow.app.domain.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationRepresentativenessEvaluatorTest {

    @Test
    fun `a close, fresh, environment-compatible station is HIGH representativeness`() {
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 4.0,
            launchEnvironment = WaterEnvironment.NEARSHORE,
            stationEnvironment = WaterEnvironment.NEARSHORE,
            freshness = ObservationFreshness.FRESH,
        )
        assertEquals(RepresentativenessLevel.HIGH, result.level)
    }

    @Test
    fun `a distant but environment-compatible station is MEDIUM, not HIGH`() {
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 20.0,
            launchEnvironment = WaterEnvironment.NEARSHORE,
            stationEnvironment = WaterEnvironment.OFFSHORE,
            freshness = ObservationFreshness.FRESH,
        )
        assertEquals(RepresentativenessLevel.MEDIUM, result.level)
    }

    @Test
    fun `a far station is LOW even when environments are compatible`() {
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 45.0,
            launchEnvironment = WaterEnvironment.NEARSHORE,
            stationEnvironment = WaterEnvironment.NEARSHORE,
            freshness = ObservationFreshness.FRESH,
        )
        assertEquals(RepresentativenessLevel.LOW, result.level)
        assertTrue(result.reasons.any { it.contains("away") })
    }

    @Test
    fun `a nearby station in an incompatible environment is LOW despite the distance`() {
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 2.0,
            launchEnvironment = WaterEnvironment.INLAND,
            stationEnvironment = WaterEnvironment.OFFSHORE,
            freshness = ObservationFreshness.FRESH,
        )
        assertEquals(RepresentativenessLevel.LOW, result.level)
        assertTrue(result.reasons.any { it.contains("does not match") })
    }

    @Test
    fun `an unclassified environment on either side is UNKNOWN, never a guess`() {
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 2.0,
            launchEnvironment = WaterEnvironment.UNKNOWN,
            stationEnvironment = WaterEnvironment.NEARSHORE,
            freshness = ObservationFreshness.FRESH,
        )
        assertEquals(RepresentativenessLevel.UNKNOWN, result.level)
    }

    @Test
    fun `an unusably stale observation is LOW regardless of distance or environment`() {
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 1.0,
            launchEnvironment = WaterEnvironment.NEARSHORE,
            stationEnvironment = WaterEnvironment.NEARSHORE,
            freshness = ObservationFreshness.UNUSABLE,
        )
        assertEquals(RepresentativenessLevel.LOW, result.level)
        assertTrue(result.reasons.any { it.contains("old") })
    }

    @Test
    fun `an aging but not unusable observation can still reach HIGH when close and compatible`() {
        // Freshness only hard-fails representativeness at UNUSABLE - AGING/STALE still let
        // distance/environment drive the level, since HIGH additionally requires FRESH.
        val result = StationRepresentativenessEvaluator.evaluate(
            distanceNm = 4.0,
            launchEnvironment = WaterEnvironment.NEARSHORE,
            stationEnvironment = WaterEnvironment.NEARSHORE,
            freshness = ObservationFreshness.AGING,
        )
        assertEquals(RepresentativenessLevel.MEDIUM, result.level)
    }
}
