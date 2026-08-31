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
) {
    /** Unknown start/end times are treated as already-active and open-ended - fail-unsafe,
     * matching RideCast's own hazard-window handling. */
    fun isActiveAt(instant: Instant): Boolean {
        val startsBefore = effective?.let { !instant.isBefore(it) } ?: true
        val endsAfter = expires?.let { !instant.isAfter(it) } ?: true
        return startsBefore && endsAfter
    }
}
