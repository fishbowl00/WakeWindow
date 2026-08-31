package com.wakewindow.app.data.remote.photon

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.io.IOException

class PhotonPlaceProvider(
    private val service: PhotonService = defaultService(),
) : MarinePlaceProvider {

    override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome =
        try {
            val response = service.search(query = query, lat = bias?.latitude, lon = bias?.longitude)
            val candidates = response.features.mapNotNull { feature ->
                val coords = feature.geometry.coordinates
                if (coords.size < 2) return@mapNotNull null
                val name = feature.properties.name ?: return@mapNotNull null
                MarinePlaceCandidate(
                    name = name,
                    location = GeoPoint(latitude = coords[1], longitude = coords[0]),
                    address = listOfNotNull(feature.properties.street, feature.properties.city, feature.properties.state)
                        .joinToString(", ")
                        .ifBlank { null },
                    guessedType = guessType(feature.properties.osm_key, feature.properties.osm_value),
                )
            }
            PlaceSearchOutcome.Success(candidates)
        } catch (e: HttpException) {
            PlaceSearchOutcome.Failure("Place search failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            PlaceSearchOutcome.Failure("Network error contacting place search", e)
        }

    private fun guessType(osmKey: String?, osmValue: String?): MarinePlaceType = when {
        osmValue == "marina" -> MarinePlaceType.MARINA
        osmValue == "boat_ramp" || osmValue == "slipway" -> MarinePlaceType.BOAT_RAMP
        osmValue == "yacht_club" -> MarinePlaceType.YACHT_CLUB
        osmValue == "dock" -> MarinePlaceType.DOCK
        osmValue == "harbour" || osmValue == "harbor" -> MarinePlaceType.HARBOR
        osmValue == "anchorage" -> MarinePlaceType.ANCHORAGE
        osmKey == "seamark" && osmValue?.contains("harbour") == true -> MarinePlaceType.HARBOR
        else -> MarinePlaceType.OTHER
    }

    companion object {
        private fun defaultService(): PhotonService {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val client = OkHttpClient.Builder().build()
            return Retrofit.Builder()
                .baseUrl("https://photon.komoot.io/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PhotonService::class.java)
        }
    }
}
