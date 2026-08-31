package com.wakewindow.app.data.place

import com.wakewindow.app.data.cache.CacheRecord
import com.wakewindow.app.data.cache.CacheStore
import com.wakewindow.app.data.cache.DurableCache
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CachedMarinePlaceProviderTest {

    private class InMemoryCacheStore : CacheStore {
        val records = mutableMapOf<String, CacheRecord>()
        override suspend fun get(key: String): CacheRecord? = records[key]
        override suspend fun put(record: CacheRecord) { records[record.key] = record }
        override suspend fun delete(key: String) { records.remove(key) }
        override suspend fun deleteExpired(now: Instant) {}
    }

    private class CountingProvider(private val outcome: PlaceSearchOutcome) : MarinePlaceProvider {
        val callCount = AtomicInteger(0)
        override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome {
            callCount.incrementAndGet()
            return outcome
        }
    }

    private val candidate = MarinePlaceCandidate("Cove Park Ramp", GeoPoint(28.408, -80.591), null, MarinePlaceType.BOAT_RAMP)

    @Test
    fun `a second identical search within the TTL never calls the delegate again`() = runBlocking {
        val delegate = CountingProvider(PlaceSearchOutcome.Success(listOf(candidate)))
        val provider = CachedMarinePlaceProvider(delegate, DurableCache(InMemoryCacheStore()), scope = this)

        val first = provider.search("canaveral") as PlaceSearchOutcome.Success
        val second = provider.search("canaveral") as PlaceSearchOutcome.Success

        assertEquals(listOf(candidate), first.candidates)
        assertEquals(listOf(candidate), second.candidates)
        assertEquals(1, delegate.callCount.get())
    }

    @Test
    fun `different bias points never share a cache entry`() = runBlocking {
        val delegate = CountingProvider(PlaceSearchOutcome.Success(listOf(candidate)))
        val provider = CachedMarinePlaceProvider(delegate, DurableCache(InMemoryCacheStore()), scope = this)

        provider.search("canaveral", GeoPoint(28.4, -80.6))
        provider.search("canaveral", GeoPoint(30.0, -82.0))

        assertEquals(2, delegate.callCount.get())
    }

    @Test
    fun `a failed search is never cached`() = runBlocking {
        val delegate = CountingProvider(PlaceSearchOutcome.Failure("down"))
        val provider = CachedMarinePlaceProvider(delegate, DurableCache(InMemoryCacheStore()), scope = this)

        val first = provider.search("canaveral")
        val second = provider.search("canaveral")

        assertTrue(first is PlaceSearchOutcome.Failure)
        assertTrue(second is PlaceSearchOutcome.Failure)
        assertEquals(2, delegate.callCount.get())
    }

    @Test
    fun `a blank query bypasses the cache entirely`() = runBlocking {
        val delegate = CountingProvider(PlaceSearchOutcome.Success(emptyList()))
        val provider = CachedMarinePlaceProvider(delegate, DurableCache(InMemoryCacheStore()), scope = this)

        provider.search("   ")
        provider.search("   ")

        assertEquals(2, delegate.callCount.get())
    }
}
