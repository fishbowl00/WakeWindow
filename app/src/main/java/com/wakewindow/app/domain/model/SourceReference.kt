package com.wakewindow.app.domain.model

import java.time.Instant

/**
 * Provenance for a single value or record pulled from an external provider. Attached to
 * forecast data, observations, tide predictions, and (eventually) facility-intelligence
 * fields, so the UI can always answer "where did this come from, and how stale/far away is
 * it" - see docs/PRODUCT.md "Data provenance."
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
)
