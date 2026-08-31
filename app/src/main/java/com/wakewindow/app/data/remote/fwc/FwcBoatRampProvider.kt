package com.wakewindow.app.data.remote.fwc

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

/**
 * A blank query is never sent as an unbounded `LIKE '%%'` scan across the whole statewide
 * inventory - too short a query is simply "no candidates" rather than an expensive, useless
 * fetch. See docs/PLACE_DISCOVERY.md.
 */
private const val MIN_QUERY_LENGTH = 2

class FwcBoatRampProvider(
    private val service: FwcService = defaultService(),
) : MarinePlaceProvider {

    override suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome {
        if (query.trim().length < MIN_QUERY_LENGTH) return PlaceSearchOutcome.Success(emptyList())
        return try {
            val response = service.search(where = FwcMapper.whereClauseFor(query))
            PlaceSearchOutcome.Success(FwcMapper.mapCandidates(response))
        } catch (e: HttpException) {
            PlaceSearchOutcome.Failure("FWC boat ramp search failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            PlaceSearchOutcome.Failure("Network error contacting FWC boat ramp inventory", e)
        }
    }

    companion object {
        private fun defaultService(): FwcService {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val client = OkHttpClient.Builder().build()
            return Retrofit.Builder()
                .baseUrl("https://gis.myfwc.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FwcService::class.java)
        }
    }
}
