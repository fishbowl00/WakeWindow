package com.wakewindow.app.data.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DurableCacheTest {

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

    private fun cache(store: InMemoryCacheStore, clock: () -> Instant) = DurableCache(store, clock)

    @Test
    fun `a cache miss calls fetch and stores the result`() = runBlocking {
        val store = InMemoryCacheStore()
        var fetchCount = 0
        val result = cache(store) { Instant.EPOCH }.getOrFetch(
            key = "a", ttl = Duration.ofMinutes(10),
            serialize = { it }, deserialize = { it },
        ) { fetchCount++; "fresh-value" }
        assertEquals("fresh-value", result)
        assertEquals(1, fetchCount)
        assertTrue(store.records.containsKey("a"))
    }

    @Test
    fun `a fresh cache hit never calls fetch`() = runBlocking {
        val store = InMemoryCacheStore()
        val now = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "cached-value", now, now.plus(Duration.ofMinutes(10)))
        var fetchCount = 0
        val result = cache(store) { now }.getOrFetch(
            key = "a", ttl = Duration.ofMinutes(10),
            serialize = { it }, deserialize = { it },
        ) { fetchCount++; "should not be called" }
        assertEquals("cached-value", result)
        assertEquals(0, fetchCount)
    }

    @Test
    fun `an expired entry is treated as a miss and refetched`() = runBlocking {
        val store = InMemoryCacheStore()
        val fetchedAt = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "stale-value", fetchedAt, fetchedAt.plus(Duration.ofMinutes(10)))
        val laterNow = fetchedAt.plus(Duration.ofMinutes(11))
        var fetchCount = 0
        val result = cache(store) { laterNow }.getOrFetch(
            key = "a", ttl = Duration.ofMinutes(10),
            serialize = { it }, deserialize = { it },
        ) { fetchCount++; "refreshed-value" }
        assertEquals("refreshed-value", result)
        assertEquals(1, fetchCount)
        assertEquals("refreshed-value", store.records["a"]?.payload)
    }

    @Test
    fun `a fetch failure propagates by default, even with a usable expired entry`() = runBlocking {
        val store = InMemoryCacheStore()
        val fetchedAt = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "stale-value", fetchedAt, fetchedAt.plus(Duration.ofMinutes(10)))
        val laterNow = fetchedAt.plus(Duration.ofMinutes(11))
        var threw = false
        try {
            cache(store) { laterNow }.getOrFetch<String>(
                key = "a", ttl = Duration.ofMinutes(10),
                serialize = { it }, deserialize = { it },
            ) { throw RuntimeException("provider down") }
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a fetch failure can fall back to a stale value when explicitly allowed`() = runBlocking {
        val store = InMemoryCacheStore()
        val fetchedAt = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "stale-value", fetchedAt, fetchedAt.plus(Duration.ofMinutes(10)))
        val laterNow = fetchedAt.plus(Duration.ofMinutes(11))
        val result = cache(store) { laterNow }.getOrFetch<String>(
            key = "a", ttl = Duration.ofMinutes(10),
            serialize = { it }, deserialize = { it },
            allowStaleOnFetchFailure = true,
        ) { throw RuntimeException("provider down") }
        assertEquals("stale-value", result)
    }

    @Test
    fun `a corrupted cache entry degrades to a miss instead of throwing`() = runBlocking {
        val store = InMemoryCacheStore()
        val now = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "not-valid-for-this-deserializer", now, now.plus(Duration.ofMinutes(10)))
        var fetchCount = 0
        val result = cache(store) { now }.getOrFetch(
            key = "a", ttl = Duration.ofMinutes(10),
            serialize = { it },
            deserialize = { throw IllegalArgumentException("malformed payload") },
        ) { fetchCount++; "fresh-value" }
        assertEquals("fresh-value", result)
        assertEquals(1, fetchCount)
    }

    @Test
    fun `a corrupted cache entry is deleted so it does not keep failing`() = runBlocking {
        val store = InMemoryCacheStore()
        val now = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "not-valid-for-this-deserializer", now, now.plus(Duration.ofMinutes(10)))
        cache(store) { now }.getOrFetch(
            key = "a", ttl = Duration.ofMinutes(10),
            serialize = { it },
            deserialize = { throw IllegalArgumentException("malformed payload") },
        ) { "fresh-value" }
        assertEquals("fresh-value", store.records["a"]?.payload)
    }

    @Test
    fun `invalidate removes a cached entry so the next call is a real miss`() = runBlocking {
        val store = InMemoryCacheStore()
        val now = Instant.parse("2026-08-31T12:00:00Z")
        store.records["a"] = CacheRecord("a", "cached-value", now, now.plus(Duration.ofMinutes(10)))
        cache(store) { now }.invalidate("a")
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `different keys never collide`() = runBlocking {
        val store = InMemoryCacheStore()
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val c = cache(store) { now }
        c.getOrFetch("a", Duration.ofMinutes(10), { it }, { it }) { "value-a" }
        c.getOrFetch("b", Duration.ofMinutes(10), { it }, { it }) { "value-b" }
        assertEquals("value-a", store.records["a"]?.payload)
        assertEquals("value-b", store.records["b"]?.payload)
    }
}
