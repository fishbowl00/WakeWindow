package com.wakewindow.app.domain.alert

/** What *kind* of consequence an alert has for a boater - not every NWS advisory threatens
 * the same thing, and treating them identically was a real Sprint 2 gap (a Heat Advisory
 * capped a score exactly like a Small Craft Advisory). See docs/MARINE_SCORING.md
 * "Alert relevance model." */
enum class AlertImpactCategory {
    /** Directly affects safe operation of a vessel on the water. */
    MARINE_NAVIGATION,
    /** A severe-weather threat that endangers the vessel/crew regardless of "marine" framing
     * (severe thunderstorms, tornadoes, tropical systems). */
    SEVERE_WEATHER,
    /** Endangers the people aboard without being a navigation hazard itself (heat, cold). */
    HUMAN_EXPOSURE,
    /** Affects the launch/return infrastructure itself (coastal flooding at a ramp/dock) more
     * than conditions on the water. */
    COASTAL_ACCESS,
    /** Worth showing, has no defensible boating-safety consequence on its own. */
    INFORMATIONAL,
    /** Not recognized by name - still surfaced, never silently dropped, but not assumed to be
     * equivalent to a marine emergency either. */
    UNKNOWN,
}

/** How an alert's impact category translates into a scoring consequence. */
enum class AlertImpactBehavior {
    /** Caps the category at [MarineAlertImpact.categoryCap] outright - the most severe
     * consequence, reserved for alerts that make going out (or staying out) genuinely
     * dangerous. */
    HARD_GATE,
    /** Caps the category at [MarineAlertImpact.categoryCap], but the underlying hazard is
     * serious rather than an emergency - the same mechanism as [HARD_GATE], kept as a
     * separate name because the two are classified from different reasoning (see
     * docs/MARINE_SCORING.md) even though they apply identically in [com.wakewindow.app.domain.scoring.MarinePointScorer]. */
    CATEGORY_CEILING,
    /** A fixed point deduction, no category cap - real but survivable. */
    SCORE_DEDUCTION,
    /** Surfaced to the user, contributes nothing to score or category. */
    INFORMATIONAL_ONLY,
}

/** A ceiling severity independent of [com.wakewindow.app.domain.scoring.BoatingCategory] so
 * this file doesn't need to depend on the scoring package - `MarinePointScorer` (which
 * already depends on `domain.alert`) maps this to a real category when applying it. */
enum class AlertSeverityCap { NO_GO, POOR, CAUTION, NONE }

data class MarineAlertImpact(
    val category: AlertImpactCategory,
    val behavior: AlertImpactBehavior,
    val categoryCap: AlertSeverityCap = AlertSeverityCap.NONE,
    val scoreDeduction: Double = 0.0,
)
