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
)
