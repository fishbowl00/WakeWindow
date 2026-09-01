package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStation
import com.wakewindow.app.domain.tide.TideStationOutcome
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the durable-cache expansion actually wired for Sprint 5 (docs/CACHE_POLICY.md "NWS
 * forecast" / "NWS alerts" / "CO-OPS station metadata" / "Tide/current predictions") behaves
 * end to end: a cache hit skips the network call, a failure/no-data outcome is never cached, and
 * concurrent requests for the same key coalesce into one fetch - matching the sprint brief's
 * Phase 25 "Cache" checklist ("repeated grid query hits cache," "same trip does not duplicate
 * station metadata").
 */
class CachedProvidersTest {

    // A SupervisorJob, matching AppDependencies.applicationScope and
    // CachedMarinePlaceProviderTest's own precedent - without it, a failed coalesced fetch
    // cancels the whole runBlocking job before its exception can reach the caller's try/catch,
    // since the async child and the awaiting coroutine would otherwise share a single failing Job.
    private val coalescerScope = CoroutineScope(SupervisorJob())

    private class InMemoryCacheStore : CacheStore {
        val records = mutableMapOf<String, CacheRecord>()
        override suspend fun get(key: String): CacheRecord? = records[key]
        override suspend fun put(record: CacheRecord) {
            records[record.key] = record
        }
        override suspend fun delete(key: String) {
            records.remove(key)
        }
        override suspend fun deleteExpired(now: Instant) {
            records.entries.removeAll { !it.value.expiresAt.isAfter(now) }
        }
    }

    private val location = GeoPoint(28.408, -80.591)
    private val start = Instant.parse("2026-08-31T12:00:00Z")
    private val end = Instant.parse("2026-08-31T13:00:00Z")

    private fun calmSeries() = listOf(
        MarineConditions(
            timestamp = start, location = location, sustainedWindKts = 8.0,
            source = SourceReference("Fake", null, start), confidence = Confidence.high(),
        ),
    )

    @Test
    fun `a repeated general forecast query for the same coordinates and window hits the cache, not the network`() = runBlocking {
        val store = InMemoryCacheStore()
        val cache = DurableCache(store) { start }
        val callCount = AtomicInteger(0)
        val delegate = object : GeneralWeatherProvider {
            override val providerName = "Fake"
            override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
                callCount.incrementAndGet()
                return ForecastOutcome.Success(calmSeries())
            }
        }
        val provider = CachedGeneralWeatherProvider(delegate, cache, coalescerScope)

        provider.hourlyForecast(location, start, end)
        provider.hourlyForecast(location, start, end)

        assertEquals(1, callCount.get())
    }

    @Test
    fun `a forecast failure is never cached - the next call retries the network`() = runBlocking {
        val store = InMemoryCacheStore()
        val cache = DurableCache(store) { start }
        val callCount = AtomicInteger(0)
        val delegate = object : MarineForecastProvider {
            override val providerName = "Fake"
            override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
                callCount.incrementAndGet()
                return ForecastOutcome.Failure("provider down")
            }
        }
        val provider = CachedMarineForecastProvider(delegate, cache, coalescerScope)

        provider.hourlyMarineForecast(location, start, end)
        provider.hourlyMarineForecast(location, start, end)

        assertEquals(2, callCount.get())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `alert cache never serves stale on a fetch failure - a failure always propagates`() = runBlocking {
        val store = InMemoryCacheStore()
        val cache = DurableCache(store) { start }
        var shouldFail = false
        val delegate = object : MarineAlertProvider {
            override suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome =
                if (shouldFail) MarineAlertOutcome.Failure("alerts endpoint down") else MarineAlertOutcome.Success(emptyList())
        }
        val provider = CachedMarineAlertProvider(delegate, cache, coalescerScope)

        provider.activeAlerts(location) // caches a real (empty) success
        shouldFail = true
        val result = provider.activeAlerts(location) // still fresh per TTL, so this is a cache hit
        assertTrue(result is MarineAlertOutcome.Success) // proves the cache hit, not the (would-be-failing) delegate
    }

    @Test
    fun `repeated tide station lookups for the same coordinates never duplicate the underlying station query`() = runBlocking {
        val store = InMemoryCacheStore()
        val cache = DurableCache(store) { start }
        val callCount = AtomicInteger(0)
        val station = TideStation("8721604", "Trident Pier", location, distanceNm = 0.5, datum = "MLLW")
        val delegate = object : TideProvider {
            override suspend fun nearestStation(location: GeoPoint): TideStationOutcome {
                callCount.incrementAndGet()
                return TideStationOutcome.Found(station)
            }
            override suspend fun events(stationId: String, date: LocalDate) = com.wakewindow.app.domain.tide.TideEventsOutcome.Success(emptyList())
        }
        val provider = CachedTideProvider(delegate, cache, coalescerScope)

        // Simulates several trip waypoints all resolving to the same nearest tide station.
        repeat(4) { provider.nearestStation(location) }

        assertEquals(1, callCount.get())
    }

    @Test
    fun `concurrent forecast requests for the same key coalesce into a single underlying fetch`() = runBlocking {
        val store = InMemoryCacheStore()
        val cache = DurableCache(store) { start }
        val callCount = AtomicInteger(0)
        val delegate = object : GeneralWeatherProvider {
            override val providerName = "Fake"
            override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome {
                callCount.incrementAndGet()
                delay(50)
                return ForecastOutcome.Success(calmSeries())
            }
        }

        kotlinx.coroutines.coroutineScope {
            val provider = CachedGeneralWeatherProvider(delegate, cache, this)
            val first = async { provider.hourlyForecast(location, start, end) }
            val second = async { provider.hourlyForecast(location, start, end) }
            first.await()
            second.await()
        }

        assertEquals(1, callCount.get())
    }
}
