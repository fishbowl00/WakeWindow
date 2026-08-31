package com.wakewindow.app.data.place

import com.wakewindow.app.data.cache.DurableCache
import com.wakewindow.app.data.cache.RequestCoalescer
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import kotlinx.coroutines.CoroutineScope
import java.time.Duration
import java.util.Locale

/**
 * Wraps any [MarinePlaceProvider] with a durable, short-TTL cache and in-process request
 * coalescing - see docs/CACHE_POLICY.md "Place searches." A moderate TTL, not a long one: place
 * search results (particularly the geocoding fallback) are cheap to refresh and a stale search
 * result is a much smaller problem than stale marine safety data, but re-fetching on every
 * keystroke for a popular query (e.g. "Port Canaveral") is still real, avoidable network cost.
 * A search failure is never cached - see [fetchOrThrow].
 */
class CachedMarinePlaceProvider(
    private val delegate: MarinePlaceProvider,
    private val cache: DurableCache,
    private val coalescer: RequestCoalescer<List<MarinePlaceCandidate>> = RequestCoalescer(),
    private val scope: CoroutineScope,
) : MarinePlaceProvider {

    override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return delegate.search(query, bias)

        val key = cacheKey(trimmed, bias)
        return try {
            val candidates = coalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key,
                    ttl = SEARCH_TTL,
                    serialize = PlaceSearchCacheCodec::encode,
                    deserialize = PlaceSearchCacheCodec::decode,
                ) { fetchOrThrow(query, bias) }
            }
            PlaceSearchOutcome.Success(candidates)
        } catch (e: PlaceSearchFailedException) {
            PlaceSearchOutcome.Failure(e.message ?: "Place search failed", e.cause)
        }
    }

    private suspend fun fetchOrThrow(query: String, bias: GeoPoint?) =
        when (val outcome = delegate.search(query, bias)) {
            is PlaceSearchOutcome.Success -> outcome.candidates
            is PlaceSearchOutcome.Failure -> throw PlaceSearchFailedException(outcome.message, outcome.cause)
        }

    private fun cacheKey(query: String, bias: GeoPoint?): String {
        val biasKey = bias?.let { String.format(Locale.US, "%.2f,%.2f", it.latitude, it.longitude) } ?: "nobias"
        return "place_search:${query.uppercase(Locale.US)}:$biasKey"
    }

    private class PlaceSearchFailedException(message: String, cause: Throwable?) : Exception(message, cause)

    companion object {
        private val SEARCH_TTL: Duration = Duration.ofMinutes(15)
    }
}
