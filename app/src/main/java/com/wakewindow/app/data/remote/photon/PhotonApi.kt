package com.wakewindow.app.data.remote.photon

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** Keyless place search - photon.komoot.io public instance. See
 * docs/RIDECAST_REFERENCE_AUDIT.md section 4 for why a free/keyless provider is acceptable
 * for this sprint's search-as-you-type UX but should be revisited before commercial release. */
interface PhotonService {
    @GET("api/")
    suspend fun search(
        @Query("q") query: String,
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null,
        @Query("limit") limit: Int = 10,
    ): PhotonResponse
}

@Serializable
data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
data class PhotonFeature(
    val properties: PhotonProperties,
    val geometry: PhotonGeometry,
)

@Serializable
data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val osm_key: String? = null,
    val osm_value: String? = null,
)

@Serializable
data class PhotonGeometry(
    val coordinates: List<Double>, // [lon, lat]
)
