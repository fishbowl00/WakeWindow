package com.wakewindow.app.data.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * De-duplicates concurrent in-flight requests for the same key within a single app process -
 * see docs/CACHE_POLICY.md "Request coalescing." If two callers ask for the same [key] while a
 * fetch for it is already running, the second caller awaits the first's result instead of
 * starting a second identical network call. This is deliberately process-local, in-memory
 * only, and unrelated to [DurableCache] (which addresses *repeat* requests over time; this
 * addresses *simultaneous* ones) - see docs/ROADMAP.md "Scale and Provider Risk" for the
 * cross-device duplicate-request problem this does *not* solve (that needs a shared backend,
 * explicitly out of scope).
 */
class RequestCoalescer<T> {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<T>>()

    suspend fun coalesce(key: String, scope: CoroutineScope, block: suspend () -> T): T {
        val deferred = mutex.withLock {
            inFlight.getOrPut(key) { scope.async { block() } }
        }
        try {
            return deferred.await()
        } finally {
            mutex.withLock {
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
        }
    }
}
