package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.domain.place.FacilityAvailability
import com.wakewindow.app.domain.place.FacilityOperationalStatus
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `whereClauseFor expands St to Saint so it matches FWC's own city spelling`() {
        // Confirmed live against gis.myfwc.com on 2026-08-31: FWC's City field is always
        // "SAINT PETERSBURG," never "ST PETERSBURG" - the abbreviated form a user would
        // naturally type must still find it.
        assertTrue(FwcMapper.whereClauseFor("St Petersburg").contains("SAINT PETERSBURG"))
        assertTrue(FwcMapper.whereClauseFor("St. Petersburg").contains("SAINT PETERSBURG"))
        assertTrue(FwcMapper.whereClauseFor("Saint Petersburg").contains("SAINT PETERSBURG"))
    }

    @Test
    fun `whereClauseFor expands Ft to Fort so it matches FWC's own city spelling`() {
        // Confirmed live: FWC's City field is "FORT MYERS"/"FORT LAUDERDALE," never "FT ...".
        assertTrue(FwcMapper.whereClauseFor("Ft Myers").contains("FORT MYERS"))
        assertTrue(FwcMapper.whereClauseFor("Ft. Lauderdale").contains("FORT LAUDERDALE"))
    }

    @Test
    fun `whereClauseFor does not mangle words that merely contain st or ft`() {
        val clause = FwcMapper.whereClauseFor("Stuart Coast")
        assertTrue(clause.contains("STUART COAST"))
        assertTrue(!clause.contains("SAINTUART"))
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

    @Test
    fun `a ramp with an OBJECTID carries it as the candidate's sourceId`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes().copy(objectId = 4821L))))
        val candidate = FwcMapper.mapCandidates(response).single()
        assertEquals("4821", candidate.sourceId)
    }

    @Test
    fun `a ramp with no OBJECTID leaves sourceId null rather than guessing`() {
        val response = FwcQueryResponse(listOf(FwcFeature(attributes().copy(objectId = null))))
        val candidate = FwcMapper.mapCandidates(response).single()
        assertNull(candidate.sourceId)
    }

    @Test
    fun `whereClauseForObjectId builds an exact-match clause`() {
        assertEquals("OBJECTID=4821", FwcMapper.whereClauseForObjectId("4821"))
    }

    @Test
    fun `whereClauseForExactName upper-cases and escapes quotes`() {
        val clause = FwcMapper.whereClauseForExactName("O'Brien's Ramp")
        assertEquals("UPPER(RampName)='O''BRIEN''S RAMP'", clause)
    }

    @Test
    fun `toFacilityInfo maps lanes, phone, ramp and access type, and amenities as verified facts`() {
        val attrs = attributes().copy(
            objectId = 99L,
            totalLanes = 2,
            contactPhone = "(321) 555-0100",
            rampType = "Paved",
            accessType = "Public",
            amenities = "Restrooms, Fish cleaning station",
        )
        val facility = FwcMapper.toFacilityInfo(attrs)
        assertEquals(2, facility.rampLanes)
        assertEquals("(321) 555-0100", facility.phone)
        assertEquals("Paved", facility.rampType)
        assertEquals("Public", facility.accessType)
        assertEquals("Restrooms, Fish cleaning station", facility.amenitiesRaw)
        assertTrue(facility.hasAnyVerifiedData)
        assertEquals("99", facility.source?.recordId)
        assertTrue(facility.source!!.isOfficial)
    }

    @Test
    fun `toFacilityInfo treats FWC's own NA placeholder phone the same as no phone`() {
        // Confirmed live against gis.myfwc.com on 2026-08-31: roughly a quarter of sampled
        // records across several Florida counties carry the literal string "NA" for
        // ContactPhone rather than leaving the field null - showing it verbatim would read as a
        // real (if garbled) phone number instead of "not available."
        val facility = FwcMapper.toFacilityInfo(attributes().copy(contactPhone = "NA"))
        assertNull(facility.phone)
    }

    @Test
    fun `toFacilityInfo leaves unpublished fields at their honest unknown default`() {
        val facility = FwcMapper.toFacilityInfo(attributes().copy(totalLanes = null, contactPhone = null))
        assertNull(facility.rampLanes)
        assertNull(facility.phone)
        assertEquals(FacilityAvailability.UNKNOWN, facility.restroom)
        assertEquals(FacilityAvailability.UNKNOWN, facility.fuel)
        assertNull(facility.gateHours)
        assertNull(facility.vhfCallingChannel)
    }

    @Test
    fun `operationalStatusOf classifies real observed FWC status text`() {
        assertEquals(FacilityOperationalStatus.OPEN, FwcMapper.operationalStatusOf("Open for Business"))
        assertEquals(FacilityOperationalStatus.CLOSED, FwcMapper.operationalStatusOf("Temporarily Closed"))
        assertEquals(FacilityOperationalStatus.CLOSED, FwcMapper.operationalStatusOf("Permanently Closed"))
    }

    @Test
    fun `operationalStatusOf never guesses at unrecognized or missing status text`() {
        assertEquals(FacilityOperationalStatus.UNKNOWN, FwcMapper.operationalStatusOf(null))
        assertEquals(FacilityOperationalStatus.UNKNOWN, FwcMapper.operationalStatusOf(""))
        assertEquals(FacilityOperationalStatus.UNKNOWN, FwcMapper.operationalStatusOf("Under construction"))
    }
}
