package com.wakewindow.app.domain.place

import com.wakewindow.app.domain.model.GeoPoint

sealed interface PlaceSearchOutcome {
    data class Success(val candidates: List<MarinePlaceCandidate>) : PlaceSearchOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : PlaceSearchOutcome
}

/** Place discovery/geocoding only - see [MarinePlace] for why this is kept separate from
 * verified facility intelligence. */
interface MarinePlaceProvider {
    suspend fun search(query: String, bias: GeoPoint? = null): PlaceSearchOutcome
}

/**
 * A launch the user has saved for quick reuse (Port Canaveral, a local ramp, etc.). Distinct
 * from [MarinePlace] itself so persistence (Room) doesn't need to store every nullable
 * facility field for a place the user may have picked purely by name/coordinate.
 */
data class SavedLaunch(
    val id: String,
    val place: MarinePlace,
    val isFavorite: Boolean = false,
    val savedAtEpochMillis: Long,
    /** The last plan actually run for this launch, so re-selecting it can suggest "your usual"
     * departure time/duration/vessel instead of a generic default - see docs/PLANNING.md
     * "Recent plans." All null until the first assessment for this launch is ever run. Hour is
     * local-time-of-day (0-23) rather than an absolute instant, since "usually leave around
     * 7am" is what's actually worth remembering, not a specific past calendar date. */
    val lastDepartureHourOfDay: Int? = null,
    val lastDurationMinutes: Long? = null,
    val lastVesselProfileId: String? = null,
)
