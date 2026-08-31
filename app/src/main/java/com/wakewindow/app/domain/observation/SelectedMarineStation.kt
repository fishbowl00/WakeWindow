package com.wakewindow.app.domain.observation

import com.wakewindow.app.domain.model.GeoPoint
import java.time.Duration
import java.time.Instant

/**
 * How much an observation's age should be trusted. NDBC buoys and coastal (C-MAN) stations
 * typically report on an hourly cadence, with some coastal stations reporting more often
 * (roughly every 6-10 minutes) and occasional gaps from sensor/transmission outages. These
 * thresholds are set with headroom above one normal hourly cycle rather than an exact SLA
 * NDBC publishes, because NDBC does not guarantee a fixed interval per station:
 *
 * - FRESH:    <= 45 min  - within one normal reporting cycle
 * - AGING:    45-90 min  - has missed roughly one expected report, still broadly useful
 * - STALE:    90-180 min - multiple missed cycles; treat as historical context, not "now"
 * - UNUSABLE: > 180 min  - conditions may have changed completely; excluded from consensus
 *
 * See docs/DATA_SOURCES.md "NDBC observation freshness policy" for the full rationale.
 */
enum class ObservationFreshness {
    FRESH,
    AGING,
    STALE,
    UNUSABLE;

    companion object {
        fun fromAge(age: Duration): ObservationFreshness = when {
            age <= Duration.ofMinutes(45) -> FRESH
            age <= Duration.ofMinutes(90) -> AGING
            age <= Duration.ofMinutes(180) -> STALE
            else -> UNUSABLE
        }
    }
}

/**
 * The station a [com.wakewindow.app.domain.marine.MarineObservationProvider] chose for a
 * query point, and why - station selection is a real decision (capability, recency, distance
 * all matter, not just "closest"), so the reasoning travels with the result rather than being
 * discarded. See docs/DATA_SOURCES.md "NDBC station selection."
 */
data class SelectedMarineStation(
    val stationId: String,
    val name: String?,
    val location: GeoPoint,
    val distanceNm: Double,
    val observedAt: Instant,
    val ageMinutes: Long,
    val freshness: ObservationFreshness,
    val hasWindData: Boolean,
    val hasWaveData: Boolean,
    val selectionReason: String,
)
