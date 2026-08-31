package com.wakewindow.app.data.remote.fwc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Florida FWC's statewide public boat ramp inventory (ArcGIS REST, MapServer layer 4) -
 * verified against live responses on 2026-08-30, see docs/DATA_SOURCES.md "Boat ramp
 * discovery." The layer's own geometry is in a Florida-specific projected CRS
 * (wkid 102967) - `Latitude`/`Longitude` attribute fields are used directly instead so no
 * reprojection is needed.
 *
 * `TotalLanes`, `Amenities`, and `ContactPhone` were identified as present in this layer's
 * schema during Sprint 3 discovery work (see docs/ROADMAP.md "Next sprint" /
 * docs/PLACE_DISCOVERY.md "What this is not") but were not yet fetched or mapped - Sprint 4
 * wires them through for `MarineFacilityInfoProvider`. `OBJECTID` (every ArcGIS layer's
 * standard row identifier) is fetched so a specific ramp record can be re-queried exactly by
 * ID later (see [MarinePlaceCandidate.sourceId]) rather than re-matching fuzzily by name.
 * **Caveat, stated honestly:** this session's outbound network access does not reach
 * `gis.myfwc.com`, so these three additional field names could not be re-verified live against
 * the current schema this sprint the way the original fields were - see
 * docs/DATA_SOURCES.md "Marine place / launch intelligence" for the full caveat. If any of the
 * three doesn't actually exist under this exact name, the DTO field simply comes back `null`
 * (the JSON converter is configured with `ignoreUnknownKeys`, and every field here is
 * nullable), degrading to [com.wakewindow.app.domain.place.FacilityAvailability.UNKNOWN] rather
 * than showing wrong data - never a fabricated value.
 */
interface FwcService {
    @GET("mapping/rest/services/Open_Data/FWC_Florida_Boat_Ramp_Inventory/MapServer/4/query")
    suspend fun search(
        @Query("where") where: String,
        @Query("outFields") outFields: String = OUT_FIELDS,
        @Query("returnGeometry") returnGeometry: Boolean = false,
        @Query("resultRecordCount") resultRecordCount: Int = 15,
        @Query("f") format: String = "json",
    ): FwcQueryResponse

    companion object {
        const val OUT_FIELDS = "OBJECTID,RampName,City,County,Latitude,Longitude,WaterBodyName,Status,RampType,AccessType,Street1,TotalLanes,Amenities,ContactPhone"
    }
}

@Serializable
data class FwcQueryResponse(val features: List<FwcFeature> = emptyList())

@Serializable
data class FwcFeature(val attributes: FwcAttributes)

@Serializable
data class FwcAttributes(
    @SerialName("OBJECTID") val objectId: Long? = null,
    @SerialName("RampName") val rampName: String? = null,
    @SerialName("City") val city: String? = null,
    @SerialName("County") val county: String? = null,
    @SerialName("Latitude") val latitude: Double? = null,
    @SerialName("Longitude") val longitude: Double? = null,
    @SerialName("WaterBodyName") val waterBodyName: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("RampType") val rampType: String? = null,
    @SerialName("AccessType") val accessType: String? = null,
    @SerialName("Street1") val street1: String? = null,
    @SerialName("TotalLanes") val totalLanes: Int? = null,
    @SerialName("Amenities") val amenities: String? = null,
    @SerialName("ContactPhone") val contactPhone: String? = null,
)
