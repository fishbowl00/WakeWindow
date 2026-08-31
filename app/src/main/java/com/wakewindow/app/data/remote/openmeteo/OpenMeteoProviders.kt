package com.wakewindow.app.data.remote.openmeteo

import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

/**
 * Development-only Open-Meteo provider. See docs/DATA_SOURCES.md: the free tier is not
 * appropriate for commercial production use, so this is one interchangeable implementation
 * behind the provider seam, never referenced directly outside [com.wakewindow.app.AppDependencies].
 */
class OpenMeteoGeneralProvider(
    private val service: OpenMeteoService = OpenMeteoConfig.generalService(),
) : GeneralWeatherProvider {

    override val providerName: String = "Open-Meteo"

    override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
        try {
            val response = service.forecast(latitude = location.latitude, longitude = location.longitude)
            val source = SourceReference(sourceName = providerName, sourceUrl = "https://open-meteo.com", retrievedAt = Instant.now())
            val all = OpenMeteoMapper.mapGeneral(response, location, source)
            ForecastOutcome.Success(all.filter { !it.timestamp.isBefore(start) && !it.timestamp.isAfter(end) })
        } catch (e: HttpException) {
            ForecastOutcome.Failure("Open-Meteo request failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            ForecastOutcome.Failure("Network error contacting Open-Meteo", e)
        }
}

class OpenMeteoMarineProvider(
    private val service: OpenMeteoMarineService = OpenMeteoConfig.marineService(),
) : MarineForecastProvider {

    override val providerName: String = "Open-Meteo Marine"

    override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
        try {
            val response = service.marineForecast(latitude = location.latitude, longitude = location.longitude)
            val source = SourceReference(sourceName = providerName, sourceUrl = "https://open-meteo.com", retrievedAt = Instant.now())
            val all = OpenMeteoMapper.mapMarine(response, location, source)
            ForecastOutcome.Success(all.filter { !it.timestamp.isBefore(start) && !it.timestamp.isAfter(end) })
        } catch (e: HttpException) {
            // Open-Meteo Marine has limited coverage away from open water - treat a 400 as
            // "no marine coverage here" rather than a hard failure.
            if (e.code() == 400) {
                ForecastOutcome.Unavailable("Open-Meteo Marine has no coverage for this location")
            } else {
                ForecastOutcome.Failure("Open-Meteo Marine request failed (HTTP ${e.code()})", e)
            }
        } catch (e: IOException) {
            ForecastOutcome.Failure("Network error contacting Open-Meteo Marine", e)
        }
}
