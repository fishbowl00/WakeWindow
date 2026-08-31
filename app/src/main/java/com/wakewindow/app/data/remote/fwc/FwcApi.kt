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
        const val OUT_FIELDS = "RampName,City,County,Latitude,Longitude,WaterBodyName,Status,RampType,AccessType,Street1"
    }
}

@Serializable
data class FwcQueryResponse(val features: List<FwcFeature> = emptyList())

@Serializable
data class FwcFeature(val attributes: FwcAttributes)

@Serializable
data class FwcAttributes(
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
)
