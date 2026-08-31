package com.wakewindow.app.data.remote.coops

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** NOAA CO-OPS Tides & Currents DTOs - verified against live responses on 2026-08-30, see
 * docs/DATA_SOURCES.md. `time_zone` must be exactly "gmt", "lst", or "lst_ldt". */
interface CoopsService {
    @GET("mdapi/prod/webapi/stations.json")
    suspend fun tideStations(@Query("type") type: String = "tidepredictions"): CoopsStationsResponse

    @GET("api/prod/datagetter")
    suspend fun predictions(
        @Query("product") product: String = "predictions",
        @Query("station") station: String,
        @Query("begin_date") beginDate: String,
        @Query("end_date") endDate: String,
        @Query("datum") datum: String = "MLLW",
        @Query("units") units: String = "english",
        @Query("time_zone") timeZone: String = "gmt",
        @Query("format") format: String = "json",
        @Query("interval") interval: String = "hilo",
    ): CoopsPredictionsResponse
}

@Serializable
data class CoopsStationsResponse(val stations: List<CoopsStationDto> = emptyList())

@Serializable
data class CoopsStationDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
)

@Serializable
data class CoopsPredictionsResponse(val predictions: List<CoopsPredictionDto> = emptyList())

@Serializable
data class CoopsPredictionDto(
    val t: String,
    val v: String,
    val type: String? = null,
)
