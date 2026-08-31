package com.wakewindow.app.data.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RequestCoalescerTest {

    @Test
    fun `two concurrent requests for the same key share a single fetch`() = runBlocking {
        val coalescer = RequestCoalescer<String>()
        val fetchCount = AtomicInteger(0)

        val first = async {
            coalescer.coalesce("same-key", this) {
                fetchCount.incrementAndGet()
                delay(50)
                "result"
            }
        }
        val second = async {
            coalescer.coalesce("same-key", this) {
                fetchCount.incrementAndGet()
                delay(50)
                "result"
            }
        }

        assertEquals("result", first.await())
        assertEquals("result", second.await())
        assertEquals(1, fetchCount.get())
    }

    @Test
    fun `requests for different keys never coalesce`() = runBlocking {
        val coalescer = RequestCoalescer<String>()
        val fetchCount = AtomicInteger(0)

        val first = async { coalescer.coalesce("key-a", this) { fetchCount.incrementAndGet(); delay(20); "a" } }
        val second = async { coalescer.coalesce("key-b", this) { fetchCount.incrementAndGet(); delay(20); "b" } }

        assertEquals("a", first.await())
        assertEquals("b", second.await())
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun `a later, sequential request for the same key fetches again once the first completed`() = runBlocking {
        val coalescer = RequestCoalescer<String>()
        val fetchCount = AtomicInteger(0)

        val firstResult = coalescer.coalesce("key", this) { fetchCount.incrementAndGet(); "one" }
        val secondResult = coalescer.coalesce("key", this) { fetchCount.incrementAndGet(); "two" }

        assertEquals("one", firstResult)
        assertEquals("two", secondResult)
        assertEquals(2, fetchCount.get())
    }
}
