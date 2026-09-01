package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.model.GeoPoint
import kotlinx.coroutines.CoroutineScope
import java.time.Duration

/**
 * Durable-caches [delegate]'s active-alerts lookup - see docs/CACHE_POLICY.md "NWS alerts."
 * **Safety-critical, so this is deliberately the shortest TTL in the whole cache expansion** (7
 * minutes, inside the sprint brief's documented 5-10 minute range) and never opts into
 * [DurableCache.getOrFetch]'s `allowStaleOnFetchFailure` - a fetch failure always propagates to
 * the caller exactly as an uncached call would (see [com.wakewindow.app.data.repository.DefaultBoatingRepository]'s
 * own "a failed alert check is NOT the same fact as zero active alerts" handling, which this
 * cache must never undermine). Only [MarineAlertOutcome.Success] is ever cached; a
 * [MarineAlertOutcome.Failure] is always re-attempted next call, never remembered. Also
 * request-coalesced - see [CachedGeneralWeatherProvider]'s doc comment for why.
 */
class CachedMarineAlertProvider(
    private val delegate: MarineAlertProvider,
    private val cache: DurableCache,
    private val scope: CoroutineScope,
    private val ttl: Duration = Duration.ofMinutes(7),
    private val coalescer: RequestCoalescer<List<MarineAlert>> = RequestCoalescer(),
) : MarineAlertProvider {

    override suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome {
        val key = "nws_alerts:${coordKey(location)}"
        return try {
            val alerts = coalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key,
                    ttl = ttl,
                    serialize = MarineAlertCacheCodec::encode,
                    deserialize = MarineAlertCacheCodec::decode,
                    allowStaleOnFetchFailure = false,
                ) {
                    val outcome = delegate.activeAlerts(location)
                    val success = outcome as? MarineAlertOutcome.Success ?: throw UncacheableAlertException(outcome)
                    success.alerts
                }
            }
            MarineAlertOutcome.Success(alerts)
        } catch (e: UncacheableAlertException) {
            e.outcome
        }
    }

    private fun coordKey(location: GeoPoint) = "%.4f,%.4f".format(location.latitude, location.longitude)

    private class UncacheableAlertException(val outcome: MarineAlertOutcome) : Exception()
}
