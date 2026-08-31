package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.observation.WaterEnvironment

/**
 * What evidence is required to award the highest categories, and it depends on where the
 * water actually is - see docs/MARINE_SCORING.md "Environment-aware evidence requirements."
 * A coastal launch with no usable wave data has a real, unaccounted-for risk (sea state);
 * an inland lake with no wave data simply has no waves to report. Treating both the same way
 * either over-penalizes the lake or under-penalizes the coast - this is the fix.
 */
object EvidenceRequirementEvaluator {

    /** Environments where sea state is a real, expected factor - missing wave data here is a
     * genuine evidence gap, not an absence of the concept. `GREAT_LAKES` is included even
     * though this sprint doesn't yet distinguish it from `INLAND` in practice (see
     * docs/STATION_REPRESENTATIVENESS.md) because the genuine Great Lakes do have real wave
     * conditions once classification improves. */
    private val WAVE_RELEVANT_ENVIRONMENTS = setOf(
        WaterEnvironment.NEARSHORE,
        WaterEnvironment.OFFSHORE,
        WaterEnvironment.HARBOR,
        WaterEnvironment.ESTUARY,
        WaterEnvironment.INTRACOASTAL,
        WaterEnvironment.GREAT_LAKES,
    )

    /** Returns a (ceiling, reason) pair when the highest category should be unreachable given
     * what's missing, or null when no ceiling applies - either because the evidence is present,
     * or because it isn't expected to exist for this environment at all. Never returns a
     * ceiling for `UNKNOWN`/`INLAND`/`RIVER` - see the class doc for why guessing is worse than
     * not gating. */
    fun evaluate(environment: WaterEnvironment, conditions: MarineConditions): Pair<BoatingCategory, String>? {
        if (environment !in WAVE_RELEVANT_ENVIRONMENTS) return null
        if (conditions.waveHeightFt != null) return null
        return BoatingCategory.GOOD to "Limited wave data prevents an Excellent rating in this coastal/offshore location"
    }
}
