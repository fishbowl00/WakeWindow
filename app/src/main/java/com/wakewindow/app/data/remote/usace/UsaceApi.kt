package com.wakewindow.app.data.remote.usace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * USACE's own "USACE Recreation Areas" ArcGIS FeatureServer (hosted item
 * `e314790ee1bb4eec982f0b669accb6fc`) - verified against live responses on 2026-08-30, see
 * docs/DATA_SOURCES.md "Boat ramp discovery." This is polygon land-parcel data around Corps
 * reservoirs, NOT a boat-ramp-specific point inventory like FWC's - `returnCentroid=true` asks
 * ArcGIS to compute a representative point per polygon so this can still be used for search/
 * distance without doing polygon math client-side. It does not itself assert a ramp exists at
 * every parcel, and [UsaceMapper] never claims [com.wakewindow.app.domain.place.MarinePlaceType.BOAT_RAMP]
 * from it for exactly that reason.
 */
interface UsaceService {
    @GET("arcgis/rest/services/usace_recreation_areas/FeatureServer/0/query")
    suspend fun search(
        @Query("where") where: String,
        @Query("outFields") outFields: String = OUT_FIELDS,
        @Query("returnGeometry") returnGeometry: Boolean = false,
        @Query("returnCentroid") returnCentroid: Boolean = true,
        @Query("outSR") outSr: Int = 4326,
        @Query("resultRecordCount") resultRecordCount: Int = 25,
        @Query("f") format: String = "json",
    ): UsaceQueryResponse

    companion object {
        const val OUT_FIELDS = "FEATURENAME,RECPROJECTSITENAME,MANAGINGAGENCY,DISTRICT"
    }
}

@Serializable
data class UsaceQueryResponse(val features: List<UsaceFeature> = emptyList())

@Serializable
data class UsaceFeature(
    val attributes: UsaceAttributes,
    val centroid: UsaceCentroid? = null,
)

@Serializable
data class UsaceAttributes(
    @SerialName("FEATURENAME") val featureName: String? = null,
    @SerialName("RECPROJECTSITENAME") val recProjectSiteName: String? = null,
    @SerialName("MANAGINGAGENCY") val managingAgency: String? = null,
    @SerialName("DISTRICT") val district: String? = null,
)

@Serializable
data class UsaceCentroid(val x: Double, val y: Double)
