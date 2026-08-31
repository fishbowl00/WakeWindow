package com.wakewindow.app.data.cache

import java.time.Duration
import java.time.Instant

/** A single cached record - opaque string [payload] so [DurableCache] never needs to know the
 * shape of what it's caching, only how to serialize/deserialize it (the caller supplies that).
 * See docs/CACHE_POLICY.md. */
data class CacheRecord(
    val key: String,
    val payload: String,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)

/** Storage seam for [DurableCache] - implemented by `RoomCacheStore` (this package) for real
 * persistence and by an in-memory fake in tests, matching the split already used for every
 * other provider in this codebase (network call behind an interface, mapper/logic
 * unit-tested separately). */
interface CacheStore {
    suspend fun get(key: String): CacheRecord?
    suspend fun put(record: CacheRecord)
    suspend fun delete(key: String)

    /** Removes every record whose `expiresAt` is at or before [now] - a light periodic
     * housekeeping pass, not required for correctness (an expired-but-not-yet-purged record is
     * simply never returned as a hit by [DurableCache.getOrFetch]). */
    suspend fun deleteExpired(now: Instant)
}

/**
 * A durable, TTL-based cache in front of a suspend fetch - see docs/CACHE_POLICY.md for the
 * rationale behind each provider's TTL. Deliberately simple: no stale-while-revalidate, no
 * background refresh, just "return the cached value if it hasn't expired, otherwise fetch and
 * store a fresh one." A fetch failure with a still-usable expired entry is the caller's
 * decision (see [getOrFetch]'s `allowStaleOnFetchFailure` - default false, since most callers
 * that reach this point already have their own provider-level fallback/failure handling and a
 * silently-served stale value would defeat that); safety-critical data (marine alerts, live
 * observations) should never set it true.
 */
class DurableCache(
    private val store: CacheStore,
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Returns the cached value for [key] if present and unexpired; otherwise calls [fetch],
     * stores the result with TTL [ttl], and returns it. A [fetch] failure only falls back to a
     * stale cached value when [allowStaleOnFetchFailure] is true - otherwise the exception
     * propagates, exactly as an uncached call would.
     */
    suspend fun <T> getOrFetch(
        key: String,
        ttl: Duration,
        serialize: (T) -> String,
        deserialize: (String) -> T,
        allowStaleOnFetchFailure: Boolean = false,
        fetch: suspend () -> T,
    ): T {
        val nowInstant = now()
        val cached = store.get(key)
        if (cached != null && cached.expiresAt.isAfter(nowInstant)) {
            return deserialize(cached.payload)
        }
        return try {
            val fresh = fetch()
            store.put(CacheRecord(key, serialize(fresh), nowInstant, nowInstant.plus(ttl)))
            fresh
        } catch (e: Exception) {
            if (allowStaleOnFetchFailure && cached != null) {
                deserialize(cached.payload)
            } else {
                throw e
            }
        }
    }

    suspend fun invalidate(key: String) = store.delete(key)
}
