package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import kotlinx.coroutines.CoroutineScope
import java.time.Duration
import java.time.Instant

/**
 * Durable-caches [delegate]'s hourly general-forecast series - see docs/CACHE_POLICY.md "NWS
 * forecast." Forecasts change on the timescale of a model run, not minute to minute, but must
 * still be refreshed regularly enough that a multi-hour-old cached value doesn't quietly go
 * stale during a live planning session - 45 minutes balances the two.
 *
 * Only a genuinely successful, non-empty fetch is ever cached - [ForecastOutcome.Unavailable]
 * (no coverage here, a permanent fact) and [ForecastOutcome.Failure] (a transient error) are
 * both re-attempted on the next call rather than remembered as if they were durable facts,
 * exactly like [com.wakewindow.app.data.remote.fwc.FwcFacilityInfoProvider]'s own cache
 * boundary.
 *
 * Also request-coalesced (see docs/CACHE_POLICY.md "Request coalescing for trip mode") - a trip
 * assessment fans out one fetch per trip point concurrently
 * ([com.wakewindow.app.data.repository.DefaultTripBoatingRepository]), and two points that
 * round to the same NWS grid coordinate and time window would otherwise both miss the cache
 * simultaneously (the first hasn't written its result yet when the second starts) and issue two
 * identical network calls; coalescing collapses them into one.
 */
class CachedGeneralWeatherProvider(
    private val delegate: GeneralWeatherProvider,
    private val cache: DurableCache,
    private val scope: CoroutineScope,
    private val ttl: Duration = Duration.ofMinutes(45),
    private val coalescer: RequestCoalescer<List<MarineConditions>> = RequestCoalescer(),
) : GeneralWeatherProvider {

    override val providerName: String get() = delegate.providerName

    override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
        val key = "nws_forecast_general:${delegate.providerName}:${coordKey(location)}:${start.epochSecond}:${end.epochSecond}"
        return try {
            val readings = coalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key,
                    ttl = ttl,
                    serialize = ForecastCacheCodec::encode,
                    deserialize = ForecastCacheCodec::decode,
                ) {
                    val outcome = delegate.hourlyForecast(location, start, end)
                    val success = outcome as? ForecastOutcome.Success
                    if (success == null || success.hourly.isEmpty()) throw UncacheableForecastException(outcome)
                    success.hourly
                }
            }
            ForecastOutcome.Success(readings)
        } catch (e: UncacheableForecastException) {
            e.outcome
        }
    }

    private fun coordKey(location: GeoPoint) = "%.4f,%.4f".format(location.latitude, location.longitude)

    private class UncacheableForecastException(val outcome: ForecastOutcome) : Exception()
}
