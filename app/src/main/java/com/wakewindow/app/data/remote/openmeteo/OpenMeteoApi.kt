package com.wakewindow.app.data.remote.openmeteo

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Open-Meteo general + Marine API DTOs. Development-time provider only - see
 * docs/DATA_SOURCES.md for the commercial-licensing constraint that keeps this entirely
 * behind the [com.wakewindow.app.domain.weather.GeneralWeatherProvider]/
 * [com.wakewindow.app.domain.marine.MarineForecastProvider] seam.
 */
interface OpenMeteoService {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "temperature_2m,apparent_temperature,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,visibility",
        @Query("temperature_unit") temperatureUnit: String = "fahrenheit",
        @Query("wind_speed_unit") windSpeedUnit: String = "kn",
        @Query("timezone") timezone: String = "UTC",
        @Query("forecast_days") forecastDays: Int = 3,
    ): OpenMeteoForecastResponse
}

interface OpenMeteoMarineService {
    @GET("v1/marine")
    suspend fun marineForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "wave_height,wave_direction,wave_period,swell_wave_height,swell_wave_direction,swell_wave_period,sea_surface_temperature,ocean_current_velocity,ocean_current_direction",
        @Query("timezone") timezone: String = "UTC",
        @Query("forecast_days") forecastDays: Int = 3,
    ): OpenMeteoMarineResponse
}

@Serializable
data class OpenMeteoForecastResponse(
    val hourly: OpenMeteoHourly? = null,
)

@Serializable
data class OpenMeteoHourly(
    val time: List<String> = emptyList(),
    val temperature_2m: List<Double?>? = null,
    val apparent_temperature: List<Double?>? = null,
    val precipitation_probability: List<Int?>? = null,
    val wind_speed_10m: List<Double?>? = null,
    val wind_gusts_10m: List<Double?>? = null,
    val wind_direction_10m: List<Double?>? = null,
    val visibility: List<Double?>? = null,
)

@Serializable
data class OpenMeteoMarineResponse(
    val hourly: OpenMeteoMarineHourly? = null,
)

@Serializable
data class OpenMeteoMarineHourly(
    val time: List<String> = emptyList(),
    val wave_height: List<Double?>? = null,
    val wave_direction: List<Double?>? = null,
    val wave_period: List<Double?>? = null,
    val swell_wave_height: List<Double?>? = null,
    val swell_wave_direction: List<Double?>? = null,
    val swell_wave_period: List<Double?>? = null,
    val sea_surface_temperature: List<Double?>? = null,
    val ocean_current_velocity: List<Double?>? = null,
    val ocean_current_direction: List<Double?>? = null,
)
