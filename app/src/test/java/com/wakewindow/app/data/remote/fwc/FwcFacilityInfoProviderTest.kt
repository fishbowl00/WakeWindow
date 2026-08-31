package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.data.cache.CacheRecord
import com.wakewindow.app.data.cache.CacheStore
import com.wakewindow.app.data.cache.DurableCache
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.FacilityInfoOutcome
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class FwcFacilityInfoProviderTest {

    private val fwcCandidate = MarinePlaceCandidate(
        name = "Cove Park Kayak Dock",
        location = GeoPoint(28.3237323, -80.61680728),
        address = null,
        guessedType = MarinePlaceType.BOAT_RAMP,
        sourceType = PlaceSourceType.FWC_BOAT_RAMP,
        sourceId = "4821",
    )

    private class FakeService(
        private val response: FwcQueryResponse = FwcQueryResponse(),
        private val error: Throwable? = null,
        val requestedWhereClauses: MutableList<String> = mutableListOf(),
    ) : FwcService {
        override suspend fun search(where: String, outFields: String, returnGeometry: Boolean, resultRecordCount: Int, format: String): FwcQueryResponse {
            requestedWhereClauses += where
            error?.let { throw it }
            return response
        }
    }

    @Test
    fun `a non-FWC candidate never triggers a lookup - honestly reports no data`() = runBlocking {
        val service = FakeService()
        val provider = FwcFacilityInfoProvider(service)
        val geocoded = fwcCandidate.copy(sourceType = PlaceSourceType.GEOCODING, sourceId = null)
        val outcome = provider.facilityInfoFor(geocoded)
        assertEquals(FacilityInfoOutcome.NoDataAvailable, outcome)
        assertTrue(service.requestedWhereClauses.isEmpty())
    }

    @Test
    fun `an FWC candidate with a sourceId is re-fetched by exact OBJECTID`() = runBlocking {
        val service = FakeService(FwcQueryResponse(listOf(FwcFeature(FwcAttributes(objectId = 4821L, rampName = "Cove Park Kayak Dock", totalLanes = 1)))))
        val provider = FwcFacilityInfoProvider(service)
        val outcome = provider.facilityInfoFor(fwcCandidate) as FacilityInfoOutcome.Success
        assertEquals(1, outcome.facility.rampLanes)
        assertEquals(listOf("OBJECTID=4821"), service.requestedWhereClauses)
    }

    @Test
    fun `an FWC candidate with no sourceId falls back to an exact name match`() = runBlocking {
        val service = FakeService(FwcQueryResponse(listOf(FwcFeature(FwcAttributes(rampName = "Cove Park Kayak Dock")))))
        val provider = FwcFacilityInfoProvider(service)
        val outcome = provider.facilityInfoFor(fwcCandidate.copy(sourceId = null))
        assertTrue(outcome is FacilityInfoOutcome.Success)
        assertEquals(listOf("UPPER(RampName)='COVE PARK KAYAK DOCK'"), service.requestedWhereClauses)
    }

    @Test
    fun `zero matching features is honestly reported as no data available, not a failure`() = runBlocking {
        val service = FakeService(FwcQueryResponse(emptyList()))
        val provider = FwcFacilityInfoProvider(service)
        val outcome = provider.facilityInfoFor(fwcCandidate)
        assertEquals(FacilityInfoOutcome.NoDataAvailable, outcome)
    }

    @Test
    fun `a network error is reported as a failure, not silently empty`() = runBlocking {
        val service = FakeService(error = IOException("boom"))
        val provider = FwcFacilityInfoProvider(service)
        val outcome = provider.facilityInfoFor(fwcCandidate)
        assertTrue(outcome is FacilityInfoOutcome.Failure)
    }

    private class InMemoryCacheStore : CacheStore {
        val records = mutableMapOf<String, CacheRecord>()
        override suspend fun get(key: String): CacheRecord? = records[key]
        override suspend fun put(record: CacheRecord) { records[record.key] = record }
        override suspend fun delete(key: String) { records.remove(key) }
        override suspend fun deleteExpired(now: Instant) {}
    }

    @Test
    fun `a second lookup within the cache TTL never calls the service again`() = runBlocking {
        val service = FakeService(FwcQueryResponse(listOf(FwcFeature(FwcAttributes(objectId = 4821L, rampName = "Cove Park Kayak Dock", totalLanes = 2)))))
        val cache = DurableCache(InMemoryCacheStore())
        val provider = FwcFacilityInfoProvider(service, cache)

        val first = provider.facilityInfoFor(fwcCandidate) as FacilityInfoOutcome.Success
        val second = provider.facilityInfoFor(fwcCandidate) as FacilityInfoOutcome.Success

        assertEquals(2, first.facility.rampLanes)
        assertEquals(2, second.facility.rampLanes)
        assertEquals(1, service.requestedWhereClauses.size)
    }

    @Test
    fun `a no-data result is never cached as if it were a permanent fact`() = runBlocking {
        val service = FakeService(FwcQueryResponse(emptyList()))
        val cache = DurableCache(InMemoryCacheStore())
        val provider = FwcFacilityInfoProvider(service, cache)

        provider.facilityInfoFor(fwcCandidate)
        provider.facilityInfoFor(fwcCandidate)

        assertEquals(2, service.requestedWhereClauses.size)
    }

    @Test
    fun `a failed lookup is never cached as if it succeeded`() = runBlocking {
        val service = FakeService(error = IOException("boom"))
        val cache = DurableCache(InMemoryCacheStore())
        val provider = FwcFacilityInfoProvider(service, cache)

        val outcome = provider.facilityInfoFor(fwcCandidate)

        assertTrue(outcome is FacilityInfoOutcome.Failure)
        assertTrue(cache.getOrFetch("fwc_facility:4821", java.time.Duration.ofDays(1), { it }, { it }) { "still-a-miss" } == "still-a-miss")
    }
}
