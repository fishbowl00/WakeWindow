package com.wakewindow.app.domain.model

/**
 * Coarse data-quality signal - not a mathematically precise probability. See
 * docs/MARINE_SCORING.md "Confidence" for the exact computation rules.
 */
enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    UNAVAILABLE,
}

/**
 * A confidence level plus the specific reasons it isn't higher - e.g. "nearest tide station
 * is 42 NM away," "no marine forecast for this inland lake." Reasons are always listed, never
 * left implicit in the bare [level].
 */
data class Confidence(
    val level: ConfidenceLevel,
    val reasons: List<String> = emptyList(),
) {
    companion object {
        fun high(): Confidence = Confidence(ConfidenceLevel.HIGH)

        fun unavailable(reason: String): Confidence =
            Confidence(ConfidenceLevel.UNAVAILABLE, listOf(reason))
    }

    /** The worse (lower) of this and [other], with reasons from both merged. */
    fun worstOf(other: Confidence): Confidence {
        val worse = if (level.ordinal >= other.level.ordinal) level else other.level
        return Confidence(worse, (reasons + other.reasons).distinct())
    }
}
