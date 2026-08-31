package com.wakewindow.app.domain.observation

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.GeoPoint

/**
 * A single current reading from a real station, distinct in kind from a forecast series - a
 * buoy reports what IS happening, not what's expected to happen eight hours from now. See
 * docs/MARINE_SCORING.md "Forecast vs. observation" for how this is (and is not) used in
 * scoring: it feeds near-term confidence and disagreement detection, never gets averaged
 * directly into a future hour's forecast value.
 */
sealed interface MarineObservationOutcome {
    data class Success(val station: SelectedMarineStation, val conditions: MarineConditions) : MarineObservationOutcome
    /** No station within a useful distance/freshness/capability envelope - a real fact about
     * this location, not a transient error. */
    data object NoStationAvailable : MarineObservationOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : MarineObservationOutcome
}

/** Observational (not forecast) marine data from a physical station - e.g. an NDBC buoy. */
interface MarineObservationProvider {
    val providerName: String

    suspend fun nearestObservation(location: GeoPoint): MarineObservationOutcome
}
