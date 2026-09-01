package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.tide.TideEvent
import com.wakewindow.app.domain.tide.TideEventsOutcome
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStation
import com.wakewindow.app.domain.tide.TideStationOutcome
import kotlinx.coroutines.CoroutineScope
import java.time.Duration
import java.time.LocalDate

/**
 * Durable-caches [delegate]'s station lookup (very long TTL - station identity/location/datum
 * essentially never changes) and per-date event predictions (a longer, hours-scale TTL - these
 * are deterministic harmonic predictions, not live readings, so re-fetching the same station/date
 * combination repeatedly across a planning session is pure waste). See docs/CACHE_POLICY.md
 * "CO-OPS station metadata" / "Tide predictions."
 *
 * Only [TideStationOutcome.Found]/[TideEventsOutcome.Success] are ever cached -
 * [TideStationOutcome.NotTidal] and any [TideStationOutcome.Failure]/[TideEventsOutcome.Failure]
 * are always re-attempted, matching every other cache boundary in this codebase. Both lookups
 * are also request-coalesced - see [CachedGeneralWeatherProvider]'s doc comment: a multi-
 * waypoint trip routinely has several points near the same tide/current station, and without
 * coalescing each would independently miss the cache before the first write lands.
 */
class CachedTideProvider(
    private val delegate: TideProvider,
    private val cache: DurableCache,
    private val scope: CoroutineScope,
    private val stationTtl: Duration = Duration.ofDays(7),
    private val eventsTtl: Duration = Duration.ofHours(6),
    private val stationCoalescer: RequestCoalescer<TideStation> = RequestCoalescer(),
    private val eventsCoalescer: RequestCoalescer<List<TideEvent>> = RequestCoalescer(),
) : TideProvider {

    override suspend fun nearestStation(location: GeoPoint): TideStationOutcome {
        val key = "coops_tide_station:${coordKey(location)}"
        return try {
            val station = stationCoalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key, ttl = stationTtl,
                    serialize = TideCacheCodec::encodeStation, deserialize = TideCacheCodec::decodeStation,
                ) {
                    val outcome = delegate.nearestStation(location)
                    (outcome as? TideStationOutcome.Found)?.station ?: throw UncacheableStationException(outcome)
                }
            }
            TideStationOutcome.Found(station)
        } catch (e: UncacheableStationException) {
            e.outcome
        }
    }

    override suspend fun events(stationId: String, date: LocalDate): TideEventsOutcome {
        val key = "coops_tide_events:$stationId:$date"
        return try {
            val events = eventsCoalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key, ttl = eventsTtl,
                    serialize = TideCacheCodec::encodeEvents, deserialize = TideCacheCodec::decodeEvents,
                ) {
                    val outcome = delegate.events(stationId, date)
                    (outcome as? TideEventsOutcome.Success)?.events ?: throw UncacheableEventsException(outcome)
                }
            }
            TideEventsOutcome.Success(events)
        } catch (e: UncacheableEventsException) {
            e.outcome
        }
    }

    private fun coordKey(location: GeoPoint) = "%.4f,%.4f".format(location.latitude, location.longitude)

    private class UncacheableStationException(val outcome: TideStationOutcome) : Exception()
    private class UncacheableEventsException(val outcome: TideEventsOutcome) : Exception()
}
