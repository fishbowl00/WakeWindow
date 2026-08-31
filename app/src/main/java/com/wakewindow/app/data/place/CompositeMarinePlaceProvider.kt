package com.wakewindow.app.data.place

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.MarinePlaceType
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
 *
 * Ranking is three-tiered (see [rank]): source authority first (FWC/USACE always outrank
 * Photon, regardless of proximity - an exact named ramp is never buried under a physically
 * closer but unverified geocoder match), then how boating-relevant the place *type* itself is
 * within that tier, then - only when a [bias] point is supplied - proximity to it. [bias] is
 * always optional: search must work identically well with no location context at all (see
 * docs/PLACE_DISCOVERY.md "Location bias").
 */
class CompositeMarinePlaceProvider(
    /** Authoritative, boating-specific sources (FWC, USACE) - always ranked above [fallback]. */
    private val boatingSources: List<MarinePlaceProvider>,
    /** General-purpose geocoding (Photon) - kept as the broad-coverage fallback, never the
     * authority for a facility a boating-specific source already identified. */
    private val fallback: MarinePlaceProvider,
) : MarinePlaceProvider {

    /** A result within this distance of an already-kept, differently-sourced result is treated
     * as the same real-world place and dropped, rather than showing the same ramp twice under
     * two sources' names - see [dedupe]. Deliberately never applied within a single source's
     * own results: two genuinely distinct facilities from the same authoritative inventory
     * (e.g. two ramps at one park) must never collapse into one just because a source's own
     * data places them close together. */
    private val duplicateDistanceNm = 0.25

    override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome = coroutineScope {
        val boatingDeferred = boatingSources.map { source -> async { runCatching { source.search(query, bias) }.getOrNull() } }
        val fallbackDeferred = async { runCatching { fallback.search(query, bias) }.getOrNull() }

        val boatingOutcomes = boatingDeferred.awaitAll().filterNotNull()
        val fallbackOutcome = fallbackDeferred.await()

        val boatingCandidates = boatingOutcomes.filterIsInstance<PlaceSearchOutcome.Success>().flatMap { it.candidates }
        val fallbackCandidates = (fallbackOutcome as? PlaceSearchOutcome.Success)?.candidates.orEmpty()

        val combined = dedupe(boatingCandidates + fallbackCandidates)
        val everyConfiguredSourceFailed = boatingSources.isNotEmpty() &&
            boatingOutcomes.size == boatingSources.size &&
            boatingOutcomes.all { it is PlaceSearchOutcome.Failure } &&
            fallbackOutcome is PlaceSearchOutcome.Failure

        if (combined.isEmpty() && everyConfiguredSourceFailed) {
            PlaceSearchOutcome.Failure("All place search sources failed")
        } else {
            PlaceSearchOutcome.Success(rank(combined, bias))
        }
    }

    /** Keeps every candidate unless a *differently-sourced* candidate already kept sits within
     * [duplicateDistanceNm] of it - the first-kept (i.e. higher source-priority, since
     * [candidates] arrives in FWC-then-USACE-then-Photon order) wins. Catches not just a
     * geocoder re-finding an FWC ramp (Sprint 3's original case) but also FWC and USACE
     * describing the same physical site, without ever merging two distinct same-source
     * records. */
    private fun dedupe(candidates: List<MarinePlaceCandidate>): List<MarinePlaceCandidate> {
        val kept = mutableListOf<MarinePlaceCandidate>()
        for (candidate in candidates) {
            val isDuplicate = kept.any { existing ->
                existing.sourceType != candidate.sourceType &&
                    existing.location.distanceNmTo(candidate.location) <= duplicateDistanceNm
            }
            if (!isDuplicate) kept += candidate
        }
        return kept
    }

    private fun rank(candidates: List<MarinePlaceCandidate>, bias: GeoPoint?): List<MarinePlaceCandidate> =
        candidates.sortedWith(
            compareBy(
                { it.sourceType.authorityRank() },
                { it.guessedType.boatingRelevanceRank() },
                { bias?.let { biasPoint -> it.location.distanceNmTo(biasPoint) } ?: 0.0 },
            ),
        )

    private fun PlaceSourceType.authorityRank(): Int = when (this) {
        PlaceSourceType.FWC_BOAT_RAMP -> 0
        PlaceSourceType.USACE_RECREATION_AREA -> 1
        PlaceSourceType.GEOCODING -> 2
    }

    /** Within a ranking tier, a place actually usable for launching/mooring a boat outranks a
     * merely maritime-adjacent one - see docs/PLACE_DISCOVERY.md "Ranking." */
    private fun MarinePlaceType.boatingRelevanceRank(): Int = when (this) {
        MarinePlaceType.BOAT_RAMP -> 0
        MarinePlaceType.MARINA -> 1
        MarinePlaceType.HARBOR -> 2
        MarinePlaceType.PORT -> 2
        MarinePlaceType.DOCK -> 3
        MarinePlaceType.ANCHORAGE -> 3
        MarinePlaceType.YACHT_CLUB -> 3
        MarinePlaceType.OTHER -> 4
    }
}
