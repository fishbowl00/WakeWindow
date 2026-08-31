package com.wakewindow.app.data.place

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import com.wakewindow.app.domain.place.PlaceSourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fans out to every configured place source concurrently and ranks results so a real,
 * government-sourced boating facility (FWC, USACE) outranks a same-named generic keyless-
 * geocoder match - see docs/PLACE_DISCOVERY.md "Ranking." One source failing never blocks the
 * others: a source that errors simply contributes zero candidates, exactly like
 * [com.wakewindow.app.data.repository.DefaultBoatingRepository]'s own provider fan-out. The
 * whole search is reported as failed only when every configured source failed - one dead
 * source alongside others that succeeded (even with zero results) is not a search failure.
 */
class CompositeMarinePlaceProvider(
    /** Authoritative, boating-specific sources (FWC, USACE) - always ranked above [fallback]. */
    private val boatingSources: List<MarinePlaceProvider>,
    /** General-purpose geocoding (Photon) - kept as the broad-coverage fallback, never the
     * authority for a facility a boating-specific source already identified. */
    private val fallback: MarinePlaceProvider,
) : MarinePlaceProvider {

    /** A geocoding result within this distance of an authoritative source's result is treated
     * as the same real-world place and dropped, rather than showing the same ramp twice. */
    private val duplicateDistanceNm = 0.25

    override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome = coroutineScope {
        val boatingDeferred = boatingSources.map { source -> async { runCatching { source.search(query, bias) }.getOrNull() } }
        val fallbackDeferred = async { runCatching { fallback.search(query, bias) }.getOrNull() }

        val boatingOutcomes = boatingDeferred.awaitAll().filterNotNull()
        val fallbackOutcome = fallbackDeferred.await()

        val boatingCandidates = boatingOutcomes.filterIsInstance<PlaceSearchOutcome.Success>().flatMap { it.candidates }
        val fallbackCandidates = (fallbackOutcome as? PlaceSearchOutcome.Success)?.candidates.orEmpty()
            .filterNot { candidate -> boatingCandidates.any { it.location.distanceNmTo(candidate.location) <= duplicateDistanceNm } }

        val combined = boatingCandidates + fallbackCandidates
        val everyConfiguredSourceFailed = boatingSources.isNotEmpty() &&
            boatingOutcomes.size == boatingSources.size &&
            boatingOutcomes.all { it is PlaceSearchOutcome.Failure } &&
            fallbackOutcome is PlaceSearchOutcome.Failure

        if (combined.isEmpty() && everyConfiguredSourceFailed) {
            PlaceSearchOutcome.Failure("All place search sources failed")
        } else {
            PlaceSearchOutcome.Success(combined.sortedBy { it.sourceType.rank() })
        }
    }

    private fun PlaceSourceType.rank(): Int = when (this) {
        PlaceSourceType.FWC_BOAT_RAMP -> 0
        PlaceSourceType.USACE_RECREATION_AREA -> 1
        PlaceSourceType.GEOCODING -> 2
    }
}
