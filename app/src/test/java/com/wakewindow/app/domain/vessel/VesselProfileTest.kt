package com.wakewindow.app.domain.vessel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the first user-selectable vessel presets (docs/MARINE_SCORING.md "Vessel profiles") -
 * every preset must be a real, distinct, internally-consistent tolerance profile, since these
 * numbers flow directly into [com.wakewindow.app.domain.scoring.MarinePointScorer] gates.
 */
class VesselProfileTest {

    @Test
    fun `there are exactly the five named presets the sprint specifies`() {
        val names = VesselProfile.presets().map { it.name }
        assertEquals(5, names.size)
        assertTrue(names.any { it.contains("Small recreational", ignoreCase = true) })
        assertTrue(names.any { it.contains("Center console", ignoreCase = true) })
        assertTrue(names.any { it.contains("Pontoon", ignoreCase = true) })
        assertTrue(names.any { it.contains("PWC", ignoreCase = true) })
        assertTrue(names.any { it.contains("Sailboat", ignoreCase = true) })
    }

    @Test
    fun `every preset has a distinct name`() {
        val names = VesselProfile.presets().map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `every preset's gust tolerance is at or above its sustained wind tolerance`() {
        // Gusts are always >= sustained wind by definition - a profile where the gust
        // tolerance is lower than the wind tolerance would be internally inconsistent.
        VesselProfile.presets().forEach { preset ->
            assertTrue("${preset.name}: gust ${preset.gustToleranceKts} < wind ${preset.windToleranceKts}", preset.gustToleranceKts >= preset.windToleranceKts)
        }
    }

    @Test
    fun `every preset has strictly positive tolerances - zero or negative would gate everything or nothing`() {
        VesselProfile.presets().forEach { preset ->
            assertTrue(preset.name, preset.windToleranceKts > 0.0)
            assertTrue(preset.name, preset.gustToleranceKts > 0.0)
            assertTrue(preset.name, preset.waveToleranceFt > 0.0)
            assertTrue(preset.name, preset.visibilityToleranceNm > 0.0)
            assertTrue(preset.name, preset.thunderstormTolerancePercent > 0)
        }
    }

    @Test
    fun `a PWC is more wind and wave sensitive than a center console despite being much smaller-seeming`() {
        val pwc = VesselProfile.presets().first { it.vesselType == VesselType.PWC }
        val centerConsole = VesselProfile.presets().first { it.vesselType == VesselType.SMALL_CENTER_CONSOLE && it.name.contains("Center console") }
        assertTrue(pwc.waveToleranceFt < centerConsole.waveToleranceFt)
        assertTrue(pwc.gustToleranceKts < centerConsole.gustToleranceKts)
    }

    @Test
    fun `the sailboat preset is not flagged as small-craft-exempt-relevant the same way a PWC is`() {
        val sailboat = VesselProfile.presets().first { it.vesselType == VesselType.SAILBOAT }
        assertTrue(!sailboat.isSmallCraft)
    }

    @Test
    fun `default() remains unaffected by preset construction - presets don't mutate shared state`() {
        val before = VesselProfile.default()
        VesselProfile.presets()
        val after = VesselProfile.default()
        assertEquals(before, after)
    }
}
