package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers mapping of the real FWC Florida Boat Ramp Inventory ArcGIS response shape - verified
 * live on 2026-08-30 near Port Canaveral (see docs/DATA_SOURCES.md "Boat ramp discovery").
 */
class FwcMapperTest {

    private fun attributes(
        rampName: String? = "Cove Park Kayak Dock",
        lat: Double? = 28.3237323,
        lon: Double? = -80.61680728,
        status: String? = "Open for Business",
        city: String? = "COCOA BEACH",
        county: String? = "BREVARD",
        street1: String? = "540 McNabb Parkway",
    ) = FwcAttributes(rampName = rampName, city = city, county = county, latitude = lat, longitude = lon, status = status, street1 = street1)

    @Test
    fun `an operational ramp maps to a BOAT_RAMP candidate sourced from FWC`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes())))
        val candidate = FwcMapper.mapCandidates(response).single()
        assertEquals("Cove Park Kayak Dock", candidate.name)
        assertEquals(MarinePlaceType.BOAT_RAMP, candidate.guessedType)
        assertEquals(PlaceSourceType.FWC_BOAT_RAMP, candidate.sourceType)
        assertEquals(28.3237323, candidate.location.latitude, 0.0001)
        assertEquals(-80.61680728, candidate.location.longitude, 0.0001)
    }

    @Test
    fun `a permanently closed ramp is excluded - showing it would imply it is usable`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes(status = "Permanently Closed"))))
        assertTrue(FwcMapper.mapCandidates(response).isEmpty())
    }

    @Test
    fun `a temporarily closed ramp is also excluded`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes(status = "Temporarily Closed"))))
        assertTrue(FwcMapper.mapCandidates(response).isEmpty())
    }

    @Test
    fun `a ramp with no coordinates is dropped rather than guessed at`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes(lat = null))))
        assertTrue(FwcMapper.mapCandidates(response).isEmpty())
    }

    @Test
    fun `a ramp with no name is dropped`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes(rampName = null))))
        assertTrue(FwcMapper.mapCandidates(response).isEmpty())
    }

    @Test
    fun `the address combines street, city, and county`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes())))
        val candidate = FwcMapper.mapCandidates(response).single()
        assertEquals("540 McNabb Parkway, COCOA BEACH, BREVARD County, FL", candidate.address)
    }

    @Test
    fun `whereClauseFor escapes single quotes so user input cannot break out of the query`() {
        val clause = FwcMapper.whereClauseFor("O'Brien's Ramp")
        assertTrue(clause.contains("O''BRIEN''S RAMP"))
        assertTrue(!clause.contains("O'BRIEN'S RAMP '"))
    }

    @Test
    fun `whereClauseFor matches across ramp name, water body, city, and county`() {
        val clause = FwcMapper.whereClauseFor("canaveral")
        assertTrue(clause.contains("RampName"))
        assertTrue(clause.contains("WaterBodyName"))
        assertTrue(clause.contains("City"))
        assertTrue(clause.contains("County"))
        assertTrue(clause.contains("CANAVERAL"))
    }
}
