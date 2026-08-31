package com.wakewindow.app.data.remote.coops

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.tide.CurrentEventsOutcome
import com.wakewindow.app.domain.tide.CurrentProvider
import com.wakewindow.app.domain.tide.CurrentStation
import com.wakewindow.app.domain.tide.CurrentStationOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Currents are hyper-local (channel/inlet-specific) in a way tide height is not - a station
 * 58 NM away says nothing useful about the current running at this launch, even though the
 * same distance might be a defensible tide-height stand-in. See docs/DATA_SOURCES.md.
 */
private const val MAX_USEFUL_CURRENT_STATION_DISTANCE_NM = 50.0

class CoopsCurrentProvider(
    private val service: CoopsService = CoopsConfig.service(),
) : CurrentProvider {

    private var cachedStations: List<CoopsCurrentStationDto>? = null
    private val stationsMutex = Mutex()

    private suspend fun stations(): List<CoopsCurrentStationDto> {
        cachedStations?.let { return it }
        return stationsMutex.withLock {
            cachedStations ?: run {
                // A station with multiple depth bins appears once per bin (same id, different
                // currbin) - keep only each id's first-listed bin, which is empirically CO-OPS'
                // own default for a bin-less predictions query (verified live 2026-08-30 against
                // FPI0901: first-listed bin 9 matches what a bin-less query returns).
                service.currentStations().stations.distinctBy { it.id }.also { cachedStations = it }
            }
        }
    }

    override suspend fun nearestStation(location: GeoPoint): CurrentStationOutcome =
        try {
            val all = stations()
            val nearest = all.minByOrNull { GeoPoint(it.lat, it.lng).distanceNmTo(location) }
            if (nearest == null) {
                CurrentStationOutcome.NoStationNearby
            } else {
                val distance = GeoPoint(nearest.lat, nearest.lng).distanceNmTo(location)
                if (distance > MAX_USEFUL_CURRENT_STATION_DISTANCE_NM) {
                    CurrentStationOutcome.NoStationNearby
                } else {
                    CurrentStationOutcome.Found(
                        CurrentStation(
                            stationId = nearest.id,
                            name = nearest.name,
                            location = GeoPoint(nearest.lat, nearest.lng),
                            distanceNm = distance,
                        ),
                    )
                }
            }
        } catch (e: HttpException) {
            CurrentStationOutcome.Failure("NOAA CO-OPS current station lookup failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            CurrentStationOutcome.Failure("Network error contacting NOAA CO-OPS", e)
        }

    override suspend fun events(stationId: String, date: LocalDate): CurrentEventsOutcome =
        try {
            val dateStr = date.format(DateTimeFormatter.BASIC_ISO_DATE)
            val response = service.currentPredictions(station = stationId, beginDate = dateStr, endDate = dateStr)
            CurrentEventsOutcome.Success(CoopsCurrentMapper.mapEvents(response))
        } catch (e: HttpException) {
            CurrentEventsOutcome.Failure("NOAA CO-OPS current predictions request failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            CurrentEventsOutcome.Failure("Network error contacting NOAA CO-OPS", e)
        }
}
