package com.wakewindow.app.domain.weather

import com.wakewindow.app.domain.model.GeoPoint
import java.time.Instant

/**
 * Ordinary weather: temperature, precipitation, thunderstorm probability, wind/gusts,
 * visibility. Implemented by NWS (`/forecast/hourly` for land points, falling back to
 * `forecastGridData` for marine-classified points where `/forecast/hourly` 404s - see
 * docs/DATA_SOURCES.md) and, during development, Open-Meteo.
 */
interface GeneralWeatherProvider {
    val providerName: String

    suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome
}
