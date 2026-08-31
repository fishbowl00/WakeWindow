package com.wakewindow.app.data.remote.ndbc

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.observation.ObservationFreshness
import java.time.Duration
import java.time.Instant

/** A station beyond this distance is not a useful observation source for a given launch -
 * NDBC coverage is sparse enough offshore that "nearest" can still be very far away, and a
 * distant reading must never be presented as representative of the user's own location. See
 * docs/DATA_SOURCES.md "NDBC station selection." */
private const val MAX_USEFUL_DISTANCE_NM = 75.0

data class StationCandidate(
    val row: NdbcObservationRow,
    val distanceNm: Double,
    val freshness: ObservationFreshness,
)

object NdbcStationSelector {

    /**
     * Not simply "closest." Ranks by, in order: freshness tier (a fresher, slightly farther
     * station beats a stale nearby one), then capability (both wind and wave beats one beats
     * neither), then distance. Returns null when nothing is within [MAX_USEFUL_DISTANCE_NM] or
     * every candidate is entirely empty of usable data.
     */
    fun select(rows: List<NdbcObservationRow>, location: GeoPoint, now: Instant): StationCandidate? {
        val candidates = rows.mapNotNull { row ->
            val distance = row.location.distanceNmTo(location)
            if (distance > MAX_USEFUL_DISTANCE_NM) return@mapNotNull null
            if (!row.hasWindData && !row.hasWaveData) return@mapNotNull null
            val age = Duration.between(row.observedAt, now)
            if (age.isNegative) return@mapNotNull null // clock skew / bad row - don't trust a "future" observation
            StationCandidate(row, distance, ObservationFreshness.fromAge(age))
        }
        return candidates.minWithOrNull(
            compareBy(
                { it.freshness.ordinal },
                { capabilityPenalty(it.row) },
                { it.distanceNm },
            ),
        )
    }

    fun selectionReason(candidate: StationCandidate): String {
        val capability = when {
            candidate.row.hasWindData && candidate.row.hasWaveData -> "wind and wave data"
            candidate.row.hasWindData -> "wind data only"
            else -> "wave data only"
        }
        return "Closest station within ${MAX_USEFUL_DISTANCE_NM.toInt()} NM reporting $capability at ${candidate.freshness.name.lowercase()} freshness"
    }

    private fun capabilityPenalty(row: NdbcObservationRow): Int = when {
        row.hasWindData && row.hasWaveData -> 0
        row.hasWindData || row.hasWaveData -> 1
        else -> 2
    }
}
