package com.wakewindow.app.data.remote.coops

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.tide.TideEvent
import com.wakewindow.app.domain.tide.TideEventType
import com.wakewindow.app.domain.tide.TideEventsOutcome
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStation
import com.wakewindow.app.domain.tide.TideStationOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A station beyond this distance is treated as "not a useful tide source for this location"
 * rather than silently presenting a far-away prediction as local. See docs/DATA_SOURCES.md.
 */
private const val MAX_USEFUL_STATION_DISTANCE_NM = 150.0

class CoopsTideProvider(
    private val service: CoopsService = CoopsConfig.service(),
) : TideProvider {

    private var cachedStations: List<CoopsStationDto>? = null
    private val stationsMutex = Mutex()

    private suspend fun stations(): List<CoopsStationDto> {
        cachedStations?.let { return it }
        return stationsMutex.withLock {
            cachedStations ?: service.tideStations().stations.also { cachedStations = it }
        }
    }

    override suspend fun nearestStation(location: GeoPoint): TideStationOutcome =
        try {
            val all = stations()
            val nearest = all.minByOrNull { GeoPoint(it.lat, it.lng).distanceNmTo(location) }
            if (nearest == null) {
                TideStationOutcome.NotTidal
            } else {
                val distance = GeoPoint(nearest.lat, nearest.lng).distanceNmTo(location)
                if (distance > MAX_USEFUL_STATION_DISTANCE_NM) {
                    TideStationOutcome.NotTidal
                } else {
                    TideStationOutcome.Found(
                        TideStation(
                            stationId = nearest.id,
                            name = nearest.name,
                            location = GeoPoint(nearest.lat, nearest.lng),
                            distanceNm = distance,
                            datum = "MLLW",
                        ),
                    )
                }
            }
        } catch (e: HttpException) {
            TideStationOutcome.Failure("NOAA CO-OPS station lookup failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            TideStationOutcome.Failure("Network error contacting NOAA CO-OPS", e)
        }

    override suspend fun events(stationId: String, date: LocalDate): TideEventsOutcome =
        try {
            val dateStr = date.format(DateTimeFormatter.BASIC_ISO_DATE)
            val response = service.predictions(station = stationId, beginDate = dateStr, endDate = dateStr)
            val events = response.predictions.mapNotNull { p ->
                val type = when (p.type) {
                    "H" -> TideEventType.HIGH
                    "L" -> TideEventType.LOW
                    else -> return@mapNotNull null
                }
                val height = p.v.toDoubleOrNull() ?: return@mapNotNull null
                val time = LocalDateTime.parse(p.t.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
                TideEvent(type, time, height)
            }
            TideEventsOutcome.Success(events)
        } catch (e: HttpException) {
            TideEventsOutcome.Failure("NOAA CO-OPS predictions request failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            TideEventsOutcome.Failure("Network error contacting NOAA CO-OPS", e)
        }
}
