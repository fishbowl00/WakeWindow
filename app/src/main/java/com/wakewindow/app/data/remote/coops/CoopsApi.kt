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

    /**
     * Current-station metadata - verified live on 2026-08-30. Most entries are harmonic
     * (subordinate) stations with `type: "H"`; a station with multiple depth bins appears once
     * per bin (same `id`, different `currbin`) - see [CoopsCurrentProvider] for how that's
     * deduplicated.
     */
    @GET("mdapi/prod/webapi/stations.json")
    suspend fun currentStations(@Query("type") type: String = "currentpredictions"): CoopsCurrentStationsResponse

    /**
     * Current predictions - verified live on 2026-08-30. `interval=MAX_SLACK` is deliberate:
     * the large majority of CO-OPS current stations are harmonic-only and do not support a
     * continuous speed curve, only the flood-max/ebb-max/slack turns - see
     * docs/DATA_SOURCES.md "Current predictions."
     */
    @GET("api/prod/datagetter")
    suspend fun currentPredictions(
        @Query("product") product: String = "currents_predictions",
        @Query("station") station: String,
        @Query("begin_date") beginDate: String,
        @Query("end_date") endDate: String,
        @Query("units") units: String = "english",
        @Query("time_zone") timeZone: String = "gmt",
        @Query("format") format: String = "json",
        @Query("interval") interval: String = "MAX_SLACK",
    ): CoopsCurrentPredictionsResponse
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

@Serializable
data class CoopsCurrentStationsResponse(val stations: List<CoopsCurrentStationDto> = emptyList())

@Serializable
data class CoopsCurrentStationDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val currbin: Int? = null,
)

@Serializable
data class CoopsCurrentPredictionsResponse(val current_predictions: CoopsCurrentPredictionsBody? = null)

@Serializable
data class CoopsCurrentPredictionsBody(val cp: List<CoopsCurrentEventDto> = emptyList())

@Serializable
data class CoopsCurrentEventDto(
    @kotlinx.serialization.SerialName("Time") val time: String,
    @kotlinx.serialization.SerialName("Type") val type: String,
    @kotlinx.serialization.SerialName("Velocity_Major") val velocityMajor: Double,
    @kotlinx.serialization.SerialName("meanFloodDir") val meanFloodDir: Double? = null,
    @kotlinx.serialization.SerialName("meanEbbDir") val meanEbbDir: Double? = null,
)
