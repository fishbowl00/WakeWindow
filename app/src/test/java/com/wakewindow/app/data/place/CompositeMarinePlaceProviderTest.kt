package com.wakewindow.app.data.place

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import com.wakewindow.app.domain.place.PlaceSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeMarinePlaceProviderTest {

    private val location = GeoPoint(28.408, -80.591)

    private fun candidate(
        name: String,
        location: GeoPoint = this.location,
        sourceType: PlaceSourceType = PlaceSourceType.GEOCODING,
        type: MarinePlaceType = MarinePlaceType.BOAT_RAMP,
    ) = MarinePlaceCandidate(name, location, null, type, sourceType)

    private class FakeProvider(private val outcome: PlaceSearchOutcome) : MarinePlaceProvider {
        override suspend fun search(query: String, bias: GeoPoint?) = outcome
    }

    private class ThrowingProvider : MarinePlaceProvider {
        override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome = throw RuntimeException("boom")
    }

    @Test
    fun `boating-sourced candidates rank ahead of geocoding candidates regardless of fetch order`() = runBlocking {
        val fwc = candidate("Cove Park Ramp", sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val geocoded = candidate("Random Marina", location = GeoPoint(29.0, -81.0), sourceType = PlaceSourceType.GEOCODING)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(FakeProvider(PlaceSearchOutcome.Success(listOf(fwc)))),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(geocoded))),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(fwc, geocoded), result.candidates)
    }

    @Test
    fun `USACE ranks below FWC but still above geocoding`() = runBlocking {
        val fwc = candidate("FWC Ramp", sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val usace = candidate("USACE Site", location = GeoPoint(29.0, -81.0), sourceType = PlaceSourceType.USACE_RECREATION_AREA)
        val geocoded = candidate("Geocoded Place", location = GeoPoint(30.0, -82.0), sourceType = PlaceSourceType.GEOCODING)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(
                FakeProvider(PlaceSearchOutcome.Success(listOf(usace))),
                FakeProvider(PlaceSearchOutcome.Success(listOf(fwc))),
            ),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(geocoded))),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(fwc, usace, geocoded), result.candidates)
    }

    @Test
    fun `a geocoding result very close to an authoritative result is dropped as a duplicate`() = runBlocking {
        val fwc = candidate("Cove Park Ramp", location = GeoPoint(28.408, -80.591), sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val duplicateGeocoded = candidate("Cove Park Boat Ramp", location = GeoPoint(28.4081, -80.5911), sourceType = PlaceSourceType.GEOCODING)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(FakeProvider(PlaceSearchOutcome.Success(listOf(fwc)))),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(duplicateGeocoded))),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(fwc), result.candidates)
    }

    @Test
    fun `a distant geocoding result is kept even when an authoritative result also exists`() = runBlocking {
        val fwc = candidate("Cove Park Ramp", location = GeoPoint(28.408, -80.591), sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val distinctGeocoded = candidate("Different Marina", location = GeoPoint(30.0, -82.0), sourceType = PlaceSourceType.GEOCODING)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(FakeProvider(PlaceSearchOutcome.Success(listOf(fwc)))),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(distinctGeocoded))),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `one boating source failing does not fail the whole search when others succeed`() = runBlocking {
        val fwc = candidate("Cove Park Ramp", sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(
                FakeProvider(PlaceSearchOutcome.Failure("USACE down")),
                FakeProvider(PlaceSearchOutcome.Success(listOf(fwc))),
            ),
            fallback = FakeProvider(PlaceSearchOutcome.Success(emptyList())),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(fwc), result.candidates)
    }

    @Test
    fun `a source that throws is treated as contributing nothing, not a crash`() = runBlocking {
        val fwc = candidate("Cove Park Ramp", sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(ThrowingProvider(), FakeProvider(PlaceSearchOutcome.Success(listOf(fwc)))),
            fallback = FakeProvider(PlaceSearchOutcome.Success(emptyList())),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(fwc), result.candidates)
    }

    @Test
    fun `every source failing is reported as a genuine failure, not silently empty results`() = runBlocking {
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(FakeProvider(PlaceSearchOutcome.Failure("FWC down"))),
            fallback = FakeProvider(PlaceSearchOutcome.Failure("Photon down")),
        )
        assertTrue(provider.search("test") is PlaceSearchOutcome.Failure)
    }

    @Test
    fun `every source succeeding with zero results is a real empty result, not a failure`() = runBlocking {
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(FakeProvider(PlaceSearchOutcome.Success(emptyList()))),
            fallback = FakeProvider(PlaceSearchOutcome.Success(emptyList())),
        )
        val result = provider.search("test")
        assertTrue(result is PlaceSearchOutcome.Success)
        assertTrue((result as PlaceSearchOutcome.Success).candidates.isEmpty())
    }

    @Test
    fun `within the geocoding tier a marina outranks a generically-typed place`() = runBlocking {
        val other = candidate("Somewhere", location = GeoPoint(29.0, -81.0), sourceType = PlaceSourceType.GEOCODING, type = MarinePlaceType.OTHER)
        val marina = candidate("A Marina", location = GeoPoint(30.0, -82.0), sourceType = PlaceSourceType.GEOCODING, type = MarinePlaceType.MARINA)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = emptyList(),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(other, marina))),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(marina, other), result.candidates)
    }

    @Test
    fun `with a bias point, results within the same tier and type rank by proximity to it`() = runBlocking {
        val near = candidate("Near Ramp", location = GeoPoint(28.41, -80.60), sourceType = PlaceSourceType.GEOCODING)
        val far = candidate("Far Ramp", location = GeoPoint(30.0, -82.0), sourceType = PlaceSourceType.GEOCODING)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = emptyList(),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(far, near))),
        )
        val bias = GeoPoint(28.408, -80.591)
        val result = provider.search("test", bias) as PlaceSearchOutcome.Success
        assertEquals(listOf(near, far), result.candidates)
    }

    @Test
    fun `with no bias point, proximity never influences ranking`() = runBlocking {
        val a = candidate("A", location = GeoPoint(28.41, -80.60), sourceType = PlaceSourceType.GEOCODING)
        val b = candidate("B", location = GeoPoint(30.0, -82.0), sourceType = PlaceSourceType.GEOCODING)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = emptyList(),
            fallback = FakeProvider(PlaceSearchOutcome.Success(listOf(a, b))),
        )
        val result = provider.search("test", bias = null) as PlaceSearchOutcome.Success
        assertEquals(listOf(a, b), result.candidates)
    }

    @Test
    fun `FWC and USACE describing the same physical site collapse into one, FWC keeping priority`() = runBlocking {
        val fwc = candidate("Cove Park Ramp", location = GeoPoint(28.408, -80.591), sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val usaceDuplicate = candidate("Cove Park Recreation Area", location = GeoPoint(28.4081, -80.5911), sourceType = PlaceSourceType.USACE_RECREATION_AREA)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(
                FakeProvider(PlaceSearchOutcome.Success(listOf(fwc))),
                FakeProvider(PlaceSearchOutcome.Success(listOf(usaceDuplicate))),
            ),
            fallback = FakeProvider(PlaceSearchOutcome.Success(emptyList())),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(listOf(fwc), result.candidates)
    }

    @Test
    fun `two distinct ramps from the same source both survive even when very close together`() = runBlocking {
        val rampOne = candidate("North Ramp", location = GeoPoint(28.4080, -80.5910), sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val rampTwo = candidate("South Ramp", location = GeoPoint(28.4081, -80.5911), sourceType = PlaceSourceType.FWC_BOAT_RAMP)
        val provider = CompositeMarinePlaceProvider(
            boatingSources = listOf(FakeProvider(PlaceSearchOutcome.Success(listOf(rampOne, rampTwo)))),
            fallback = FakeProvider(PlaceSearchOutcome.Success(emptyList())),
        )
        val result = provider.search("test") as PlaceSearchOutcome.Success
        assertEquals(2, result.candidates.size)
    }
}
