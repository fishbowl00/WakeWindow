package com.wakewindow.app.domain.marine

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.weather.ForecastOutcome
import java.time.Instant

/**
 * Wind/wave/swell forecast. Implemented by NWS `forecastGridData` (waveHeight,
 * primarySwellHeight/Direction, twentyFootWindSpeed, etc. - populated only for
 * marine-classified grid points, see docs/DATA_SOURCES.md) and, during development,
 * Open-Meteo's Marine API (`marine-api.open-meteo.com`) - see docs/DATA_SOURCES.md for the
 * commercial-licensing constraint on the latter.
 */
interface MarineForecastProvider {
    val providerName: String

    suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome
}
