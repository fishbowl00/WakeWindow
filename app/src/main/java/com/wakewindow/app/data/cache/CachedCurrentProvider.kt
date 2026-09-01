package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.tide.CurrentEvent
import com.wakewindow.app.domain.tide.CurrentEventsOutcome
import com.wakewindow.app.domain.tide.CurrentProvider
import com.wakewindow.app.domain.tide.CurrentStation
import com.wakewindow.app.domain.tide.CurrentStationOutcome
import kotlinx.coroutines.CoroutineScope
import java.time.Duration
import java.time.LocalDate

/** The current-prediction counterpart to [CachedTideProvider] - identical TTL and request-
 * coalescing rationale (station metadata very long, per-date predictions longer/hours-scale) -
 * see docs/CACHE_POLICY.md "CO-OPS current predictions." */
class CachedCurrentProvider(
    private val delegate: CurrentProvider,
    private val cache: DurableCache,
    private val scope: CoroutineScope,
    private val stationTtl: Duration = Duration.ofDays(7),
    private val eventsTtl: Duration = Duration.ofHours(6),
    private val stationCoalescer: RequestCoalescer<CurrentStation> = RequestCoalescer(),
    private val eventsCoalescer: RequestCoalescer<List<CurrentEvent>> = RequestCoalescer(),
) : CurrentProvider {

    override suspend fun nearestStation(location: GeoPoint): CurrentStationOutcome {
        val key = "coops_current_station:${coordKey(location)}"
        return try {
            val station = stationCoalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key, ttl = stationTtl,
                    serialize = CurrentCacheCodec::encodeStation, deserialize = CurrentCacheCodec::decodeStation,
                ) {
                    val outcome = delegate.nearestStation(location)
                    (outcome as? CurrentStationOutcome.Found)?.station ?: throw UncacheableStationException(outcome)
                }
            }
            CurrentStationOutcome.Found(station)
        } catch (e: UncacheableStationException) {
            e.outcome
        }
    }

    override suspend fun events(stationId: String, date: LocalDate): CurrentEventsOutcome {
        val key = "coops_current_events:$stationId:$date"
        return try {
            val events = eventsCoalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key, ttl = eventsTtl,
                    serialize = CurrentCacheCodec::encodeEvents, deserialize = CurrentCacheCodec::decodeEvents,
                ) {
                    val outcome = delegate.events(stationId, date)
                    (outcome as? CurrentEventsOutcome.Success)?.events ?: throw UncacheableEventsException(outcome)
                }
            }
            CurrentEventsOutcome.Success(events)
        } catch (e: UncacheableEventsException) {
            e.outcome
        }
    }

    private fun coordKey(location: GeoPoint) = "%.4f,%.4f".format(location.latitude, location.longitude)

    private class UncacheableStationException(val outcome: CurrentStationOutcome) : Exception()
    private class UncacheableEventsException(val outcome: CurrentEventsOutcome) : Exception()
}
