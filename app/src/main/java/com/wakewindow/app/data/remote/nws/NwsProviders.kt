package com.wakewindow.app.data.remote.nws

import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * NWS-backed [GeneralWeatherProvider], [MarineForecastProvider], and [MarineAlertProvider],
 * sharing one grid-resolution cache and one `forecastGridData` fetch since both general and
 * marine fields live in the same response - see docs/DATA_SOURCES.md and NwsApi.kt.
 *
 * The `/points/{lat,lon}` -> grid-URL lookup is cached indefinitely per coordinate (pure
 * geography, never goes stale), mirroring RideCast's own NWS client - see
 * docs/RIDECAST_REFERENCE_AUDIT.md section 1.
 */
class NwsProviders(
    private val service: NwsService = NwsConfig.service(),
) : GeneralWeatherProvider, MarineForecastProvider, MarineAlertProvider {

    override val providerName: String = "National Weather Service"

    private val gridUrlCache = ConcurrentHashMap<String, String>()
    private val zoneIdCache = ConcurrentHashMap<String, ZoneId>()

    private suspend fun resolvePoints(location: GeoPoint): NwsPointsProperties {
        val key = coordKey(location)
        val points = service.points(coordPath(location)).properties
        points.forecastGridData?.let { gridUrlCache[key] = it }
        points.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull()?.let { zone -> zoneIdCache[key] = zone } }
        return points
    }

    private suspend fun resolveGridDataUrl(location: GeoPoint): String {
        val key = coordKey(location)
        gridUrlCache[key]?.let { return it }
        return resolvePoints(location).forecastGridData
            ?: throw IllegalStateException("NWS did not return a forecastGridData URL for this point")
    }

    /** Resolves the time zone AT the given location (not the device's) - see
     * docs/ARCHITECTURE.md "Time zone handling." Falls back to the device zone only if NWS
     * has no coverage at all for the point (e.g. far outside the US). */
    suspend fun resolveZoneId(location: GeoPoint): ZoneId {
        val key = coordKey(location)
        zoneIdCache[key]?.let { return it }
        return try {
            resolvePoints(location)
            zoneIdCache[key] ?: ZoneId.systemDefault()
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }
    }

    override suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
        fetchGridConditions(location, start, end)

    override suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
        fetchGridConditions(location, start, end)

    private suspend fun fetchGridConditions(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome =
        try {
            val url = resolveGridDataUrl(location)
            val response = service.gridpointsData(url)
            val hours = hourlySequence(start, end)
            val source = SourceReference(sourceName = providerName, sourceUrl = url, retrievedAt = Instant.now())
            ForecastOutcome.Success(NwsMapper.mapGridpointsToMarineConditions(response.properties, hours, location, source))
        } catch (e: HttpException) {
            if (e.code() == 404) {
                ForecastOutcome.Unavailable("NWS has no forecast grid coverage for this location")
            } else {
                ForecastOutcome.Failure("NWS request failed (HTTP ${e.code()})", e)
            }
        } catch (e: IOException) {
            ForecastOutcome.Failure("Network error contacting NWS", e)
        }

    override suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome =
        try {
            val response = service.activeAlerts("${location.latitude},${location.longitude}")
            MarineAlertOutcome.Success(NwsMapper.mapAlerts(response))
        } catch (e: HttpException) {
            MarineAlertOutcome.Failure("NWS alerts request failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            MarineAlertOutcome.Failure("Network error contacting NWS alerts", e)
        }

    private fun coordKey(location: GeoPoint) = "%.4f,%.4f".format(location.latitude, location.longitude)
    private fun coordPath(location: GeoPoint) = "%.4f,%.4f".format(location.latitude, location.longitude)

    private fun hourlySequence(start: Instant, end: Instant): List<Instant> {
        val hours = mutableListOf<Instant>()
        var t = start.truncatedTo(ChronoUnit.HOURS)
        val last = end.truncatedTo(ChronoUnit.HOURS)
        while (!t.isAfter(last)) {
            hours += t
            t = t.plus(1, ChronoUnit.HOURS)
        }
        return hours
    }
}
