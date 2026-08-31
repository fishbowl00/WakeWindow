package com.wakewindow.app.domain.model

import java.time.Instant

/** Where a piece of information came from, roughly in order of how much weight it deserves.
 * See docs/DATA_SOURCES.md "Launch intelligence source types." Favor official sources
 * whenever more than one is available for the same fact. */
enum class SourceType {
    OFFICIAL_PORT,
    MUNICIPAL,
    STATE_AGENCY,
    USACE,
    USCG,
    MARINA_OPERATOR,
    THIRD_PARTY,
    USER_PROVIDED,
}

/**
 * Provenance for a single value or record pulled from an external provider. Attached to
 * forecast data, observations, tide predictions, and facility-intelligence fields, so the UI
 * can always answer "where did this come from, and how stale/far away/authoritative is it" -
 * see docs/PRODUCT.md "Data provenance."
 */
data class SourceReference(
    val sourceName: String,
    val sourceUrl: String?,
    val retrievedAt: Instant,
    /** Set when this value came from a physical station (buoy, tide gauge) rather than a gridded forecast. */
    val stationId: String? = null,
    val stationName: String? = null,
    val stationDistanceNm: Double? = null,
    /** Set only for a human-verified fact (e.g. a launch's ramp fee), not a live data feed. */
    val verifiedAt: Instant? = null,
    val sourceType: SourceType? = null,
    val isOfficial: Boolean = false,
)
