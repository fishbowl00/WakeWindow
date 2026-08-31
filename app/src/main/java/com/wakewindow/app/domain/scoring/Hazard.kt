package com.wakewindow.app.domain.scoring

import java.time.Instant

enum class HazardType {
    MARINE_ALERT_EXTREME,
    MARINE_ALERT_SEVERE,
    MARINE_ALERT_ADVISORY,
    THUNDERSTORM,
    WAVE_HEIGHT,
    GUST,
    VISIBILITY,
}

/**
 * A single hazard that applied at a specific hour, and what it did to the category. Every
 * [BoatingCategory] worse than EXCELLENT must be traceable to a list of these - see
 * docs/MARINE_SCORING.md "explainable scoring." [message] is a complete, human-readable
 * sentence (e.g. "Wind gusts reaching 24 kt after 2 PM"), not a code the UI has to translate.
 */
data class Hazard(
    val type: HazardType,
    val message: String,
    val at: Instant,
    val value: Double? = null,
    val threshold: Double? = null,
    /** The category this hazard alone caps the point at, if it's a gate. Null for a hazard
     * that only contributed to a numeric deduction, not an outright cap. */
    val categoryCap: BoatingCategory? = null,
)
