package com.wakewindow.app.data.remote.nws

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * api.weather.gov DTOs and Retrofit interface. Field names deliberately match the API's own
 * JSON keys - verified against live responses on 2026-08-30, see docs/DATA_SOURCES.md.
 *
 * Both general weather and marine forecast fields come from the single `forecastGridData`
 * endpoint (`gridpointsData`) rather than `/forecast`/`/forecast/hourly`: that endpoint
 * 404s with `MarineForecastNotSupported` for any point NWS classifies as `type: marine`
 * (confirmed live), while `forecastGridData` returns numeric hourly data - land or marine -
 * from one consistent, precisely-timed source. A land grid simply leaves wave/swell
 * properties empty rather than needing a different code path.
 */
interface NwsService {
    @GET("points/{point}")
    suspend fun points(@Path("point") point: String): NwsPointsResponse

    @GET
    suspend fun gridpointsData(@Url url: String): NwsGridpointsResponse

    @GET("alerts/active")
    suspend fun activeAlerts(@Query("point") point: String): NwsAlertsResponse
}

@Serializable
data class NwsPointsResponse(val properties: NwsPointsProperties)

@Serializable
data class NwsPointsProperties(
    val cwa: String? = null,
    val type: String? = null, // "land" or "marine"
    val gridId: String? = null,
    val gridX: Int? = null,
    val gridY: Int? = null,
    val forecastGridData: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class NwsGridpointsResponse(val properties: NwsGridpointsProperties)

@Serializable
data class NwsGridQuantitative(
    val uom: String? = null,
    val values: List<NwsGridValue> = emptyList(),
)

@Serializable
data class NwsGridValue(
    val validTime: String,
    val value: Double? = null,
)

@Serializable
data class NwsGridpointsProperties(
    val temperature: NwsGridQuantitative? = null,
    val apparentTemperature: NwsGridQuantitative? = null,
    val windSpeed: NwsGridQuantitative? = null,
    val windGust: NwsGridQuantitative? = null,
    val windDirection: NwsGridQuantitative? = null,
    val visibility: NwsGridQuantitative? = null,
    val probabilityOfPrecipitation: NwsGridQuantitative? = null,
    val probabilityOfThunder: NwsGridQuantitative? = null,
    val waveHeight: NwsGridQuantitative? = null,
    val waveDirection: NwsGridQuantitative? = null,
    val wavePeriod: NwsGridQuantitative? = null,
    val primarySwellHeight: NwsGridQuantitative? = null,
    val primarySwellDirection: NwsGridQuantitative? = null,
    val twentyFootWindSpeed: NwsGridQuantitative? = null,
    val twentyFootWindDirection: NwsGridQuantitative? = null,
)

@Serializable
data class NwsAlertsResponse(val features: List<NwsAlertFeature> = emptyList())

@Serializable
data class NwsAlertFeature(val properties: NwsAlertProperties)

@Serializable
data class NwsAlertProperties(
    val id: String? = null,
    val event: String? = null,
    val headline: String? = null,
    val severity: String? = null,
    val effective: String? = null,
    val onset: String? = null,
    val expires: String? = null,
    val ends: String? = null,
    val areaDesc: String? = null,
)
