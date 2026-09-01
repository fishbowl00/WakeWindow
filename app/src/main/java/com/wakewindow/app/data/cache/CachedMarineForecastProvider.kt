package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.weather.ForecastOutcome
import kotlinx.coroutines.CoroutineScope
import java.time.Duration
import java.time.Instant

/** The marine-forecast counterpart to [CachedGeneralWeatherProvider] - same TTL and request-
 * coalescing rationale (docs/CACHE_POLICY.md "NWS forecast" / "Request coalescing for trip
 * mode"), same "only cache a real success" boundary. Kept as a distinct cache-key prefix from
 * the general forecast even when both wrap the same underlying
 * [com.wakewindow.app.data.remote.nws.NwsProviders] instance, since a caller may want general
 * data without marine data (an inland lake) or vice versa. */
class CachedMarineForecastProvider(
    private val delegate: MarineForecastProvider,
    private val cache: DurableCache,
    private val scope: CoroutineScope,
    private val ttl: Duration = Duration.ofMinutes(45),
    private val coalescer: RequestCoalescer<List<MarineConditions>> = RequestCoalescer(),
) : MarineForecastProvider {

    override val providerName: String get() = delegate.providerName

    override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
        val key = "nws_forecast_marine:${delegate.providerName}:${coordKey(location)}:${start.epochSecond}:${end.epochSecond}"
        return try {
            val readings = coalescer.coalesce(key, scope) {
                cache.getOrFetch(
                    key = key,
                    ttl = ttl,
                    serialize = ForecastCacheCodec::encode,
                    deserialize = ForecastCacheCodec::decode,
                ) {
                    val outcome = delegate.hourlyMarineForecast(location, start, end)
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
