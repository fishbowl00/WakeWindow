package com.wakewindow.app.data.remote.usace

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException

private const val MIN_QUERY_LENGTH = 2

class UsaceRecreationProvider(
    private val service: UsaceService = defaultService(),
) : MarinePlaceProvider {

    override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome {
        if (query.trim().length < MIN_QUERY_LENGTH) return PlaceSearchOutcome.Success(emptyList())
        return try {
            val response = service.search(where = UsaceMapper.whereClauseFor(query))
            PlaceSearchOutcome.Success(UsaceMapper.mapCandidates(response))
        } catch (e: HttpException) {
            PlaceSearchOutcome.Failure("USACE recreation area search failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            PlaceSearchOutcome.Failure("Network error contacting USACE recreation areas", e)
        }
    }

    companion object {
        private fun defaultService(): UsaceService {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val client = OkHttpClient.Builder().build()
            return Retrofit.Builder()
                .baseUrl("https://services7.arcgis.com/n1YM8pTrFmm7L4hs/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(UsaceService::class.java)
        }
    }
}
