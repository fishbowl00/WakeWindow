package com.wakewindow.app.data.remote.usace

import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers mapping of the real USACE recreation-areas ArcGIS response shape - verified live on
 * 2026-08-30 near Table Rock Lake (see docs/DATA_SOURCES.md "Boat ramp discovery"). This
 * dataset is polygon land parcels, not a boat-ramp-specific inventory - the mapper must never
 * claim [MarinePlaceType.BOAT_RAMP] from it.
 */
class UsaceMapperTest {

    private fun feature(
        siteName: String? = "TABLE ROCK LAKE",
        featureName: String? = null,
        district: String? = "SWL",
        x: Double? = -93.2994143650422,
        y: Double? = 36.59821704136346,
    ) = UsaceFeature(
        attributes = UsaceAttributes(featureName = featureName, recProjectSiteName = siteName, district = district),
        centroid = if (x != null && y != null) UsaceCentroid(x, y) else null,
    )

    @Test
    fun `a recreation-area parcel maps to an OTHER-typed candidate sourced from USACE - never claims BOAT_RAMP`() {
        val response = UsaceQueryResponse(listOf(feature()))
        val candidate = UsaceMapper.mapCandidates(response).single()
        assertEquals(MarinePlaceType.OTHER, candidate.guessedType)
        assertEquals(PlaceSourceType.USACE_RECREATION_AREA, candidate.sourceType)
    }

    @Test
    fun `the site name is title-cased for display, not shown shouting in all caps`() {
        val response = UsaceQueryResponse(listOf(feature(siteName = "TABLE ROCK LAKE")))
        assertEquals("Table Rock Lake", UsaceMapper.mapCandidates(response).single().name)
    }

    @Test
    fun `the centroid x,y maps to longitude,latitude - not swapped`() {
        val response = UsaceQueryResponse(listOf(feature(x = -93.3, y = 36.6)))
        val location = UsaceMapper.mapCandidates(response).single().location
        assertEquals(36.6, location.latitude, 0.0001)
        assertEquals(-93.3, location.longitude, 0.0001)
    }

    @Test
    fun `multiple parcels for the same reservoir collapse to a single result`() {
        val response = UsaceQueryResponse(
            listOf(
                feature(siteName = "TABLE ROCK LAKE", x = -93.30, y = 36.598),
                feature(siteName = "TABLE ROCK LAKE", x = -93.31, y = 36.609),
                feature(siteName = "TABLE ROCK LAKE", x = -93.31, y = 36.593),
            ),
        )
        assertEquals(1, UsaceMapper.mapCandidates(response).size)
    }

    @Test
    fun `distinct reservoirs remain distinct results`() {
        val response = UsaceQueryResponse(listOf(feature(siteName = "TABLE ROCK LAKE"), feature(siteName = "BEAVER LAKE", x = -94.0, y = 36.4)))
        assertEquals(2, UsaceMapper.mapCandidates(response).size)
    }

    @Test
    fun `a parcel with no centroid is dropped rather than guessed at`() {
        val response = UsaceQueryResponse(listOf(feature(x = null, y = null)))
        assertTrue(UsaceMapper.mapCandidates(response).isEmpty())
    }

    @Test
    fun `a parcel with no usable name at all is dropped`() {
        val response = UsaceQueryResponse(listOf(feature(siteName = null, featureName = null)))
        assertTrue(UsaceMapper.mapCandidates(response).isEmpty())
    }

    @Test
    fun `the address attributes the managing USACE district`() {
        val response = UsaceQueryResponse(listOf(feature(district = "SWL")))
        assertEquals("USACE SWL District", UsaceMapper.mapCandidates(response).single().address)
    }
}
