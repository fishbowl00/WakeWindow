package com.wakewindow.app.domain.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterEnvironmentClassifierTest {

    @Test
    fun `an NWS marine point classifies as nearshore`() {
        assertEquals(WaterEnvironment.NEARSHORE, WaterEnvironmentClassifier.classify("marine", null))
    }

    @Test
    fun `a land point close to a tide station classifies as harbor`() {
        assertEquals(WaterEnvironment.HARBOR, WaterEnvironmentClassifier.classify("land", 3.0))
    }

    @Test
    fun `a land point moderately close to a tide station classifies as estuary`() {
        assertEquals(WaterEnvironment.ESTUARY, WaterEnvironmentClassifier.classify("land", 15.0))
    }

    @Test
    fun `a land point far from any tide station classifies as inland`() {
        assertEquals(WaterEnvironment.INLAND, WaterEnvironmentClassifier.classify("land", 60.0))
    }

    @Test
    fun `a land point with no tide station at all classifies as inland`() {
        assertEquals(WaterEnvironment.INLAND, WaterEnvironmentClassifier.classify("land", null))
    }

    @Test
    fun `an unresolvable point type classifies as unknown rather than guessing`() {
        assertEquals(WaterEnvironment.UNKNOWN, WaterEnvironmentClassifier.classify(null, 3.0))
        assertEquals(WaterEnvironment.UNKNOWN, WaterEnvironmentClassifier.classify("something-else", 3.0))
    }

    @Test
    fun `unknown is never compatible with anything, including itself`() {
        assertFalse(WaterEnvironmentClassifier.areCompatible(WaterEnvironment.UNKNOWN, WaterEnvironment.UNKNOWN))
        assertFalse(WaterEnvironmentClassifier.areCompatible(WaterEnvironment.UNKNOWN, WaterEnvironment.HARBOR))
    }

    @Test
    fun `harbor and nearshore are compatible - both part of the coastal cluster`() {
        assertTrue(WaterEnvironmentClassifier.areCompatible(WaterEnvironment.HARBOR, WaterEnvironment.NEARSHORE))
    }

    @Test
    fun `inland and offshore are not compatible`() {
        assertFalse(WaterEnvironmentClassifier.areCompatible(WaterEnvironment.INLAND, WaterEnvironment.OFFSHORE))
    }

    @Test
    fun `the same environment is always compatible with itself`() {
        assertTrue(WaterEnvironmentClassifier.areCompatible(WaterEnvironment.INLAND, WaterEnvironment.INLAND))
    }
}
