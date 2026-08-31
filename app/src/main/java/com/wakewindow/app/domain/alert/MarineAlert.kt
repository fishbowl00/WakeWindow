package com.wakewindow.app.domain.alert

import java.time.Instant

/**
 * Coarse severity tiers derived from NWS CAP alert `event` text, matching the tiering
 * RideCast uses for its own hazard evaluation (see docs/RIDECAST_REFERENCE_AUDIT.md section
 * 7) - extended here with marine-specific event types.
 */
enum class MarineAlertSeverity {
    /** Hurricane/Tropical Storm Warning, Special Marine Warning currently in effect. */
    EXTREME,
    /** Gale Warning, Storm Warning. */
    SEVERE,
    /** Small Craft Advisory. */
    ADVISORY,
    /** A watch: conditions favorable but not yet occurring. */
    WATCH,
    UNKNOWN,
}

data class MarineAlert(
    val id: String,
    val event: String,
    val headline: String?,
    val severity: MarineAlertSeverity,
    val effective: Instant?,
    val expires: Instant?,
    val areaDescription: String?,
    /**
     * **Sprint 3 reversal - kept only for source compatibility, no longer consulted by
     * scoring.** This used to let a vessel above `VesselProfile.isSmallCraft` skip a Small
     * Craft Advisory gate entirely. Per docs/MARINE_SCORING.md "Alert relevance model," an
     * active Small Craft Advisory is now always surfaced and always applies its category
     * ceiling, regardless of vessel size - vessel profile may reasonably affect assessment
     * severity in the future, but must never hide or ignore an active advisory outright.
     */
    val vesselSizeExemptApplicable: Boolean = false,
    /** What kind of consequence this alert has and how it should affect scoring - see
     * [MarineAlertImpact] and docs/MARINE_SCORING.md "Alert relevance model." This is what
     * [com.wakewindow.app.domain.scoring.MarinePointScorer] actually gates on; [severity] is
     * kept for simpler display/legacy purposes. */
    val impact: MarineAlertImpact,
) {
    /** Unknown start/end times are treated as already-active and open-ended - fail-unsafe,
     * matching RideCast's own hazard-window handling. */
    fun isActiveAt(instant: Instant): Boolean {
        val startsBefore = effective?.let { !instant.isBefore(it) } ?: true
        val endsAfter = expires?.let { !instant.isAfter(it) } ?: true
        return startsBefore && endsAfter
    }

    /** Whether this alert overlaps ANY instant in [outingStart, outingEnd] - the gate applies
     * only when this is true, which is exactly "active during the planned outing," not
     * "active at this exact instant." See [AlertTiming] for the full relevance breakdown. */
    fun overlaps(outingStart: Instant, outingEnd: Instant): Boolean {
        val effectiveStart = effective ?: Instant.MIN
        val effectiveEnd = expires ?: Instant.MAX
        return !effectiveStart.isAfter(outingEnd) && !effectiveEnd.isBefore(outingStart)
    }
}

/** How an alert relates to *right now* vs. the user's planned outing window - a distinct
 * concept from whether it gates the score (gating only cares about overlap with the outing).
 * Used purely for UI explanation ("this warning starts during your outing, not right now"). */
enum class AlertTiming {
    ACTIVE_NOW,
    STARTS_DURING_OUTING,
    ALREADY_EXPIRED,
    OUTSIDE_OUTING_WINDOW,
}

fun MarineAlert.timingRelativeTo(now: Instant, outingStart: Instant, outingEnd: Instant): AlertTiming {
    if (isActiveAt(now)) return AlertTiming.ACTIVE_NOW
    if (expires != null && expires.isBefore(now)) return AlertTiming.ALREADY_EXPIRED
    if (overlaps(outingStart, outingEnd)) return AlertTiming.STARTS_DURING_OUTING
    return AlertTiming.OUTSIDE_OUTING_WINDOW
}
