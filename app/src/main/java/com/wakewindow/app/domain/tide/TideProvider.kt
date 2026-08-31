package com.wakewindow.app.domain.tide

import com.wakewindow.app.domain.model.GeoPoint
import java.time.LocalDate

sealed interface TideStationOutcome {
    data class Found(val station: TideStation) : TideStationOutcome
    /** No tidal station applies here at all (e.g. a non-tidal inland lake) - a permanent fact, not an error. */
    data object NotTidal : TideStationOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : TideStationOutcome
}

sealed interface TideEventsOutcome {
    data class Success(val events: List<TideEvent>) : TideEventsOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : TideEventsOutcome
}

sealed interface CurrentStationOutcome {
    data class Found(val station: CurrentStation) : CurrentStationOutcome
    data object NoStationNearby : CurrentStationOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : CurrentStationOutcome
}

/**
 * Tide predictions from a NOAA CO-OPS station. The nearest *tide* station and nearest
 * *current* station for the same launch are frequently not the same physical facility - see
 * [CurrentProvider] and docs/DATA_SOURCES.md.
 */
interface TideProvider {
    suspend fun nearestStation(location: GeoPoint): TideStationOutcome
    suspend fun events(stationId: String, date: LocalDate): TideEventsOutcome
}

interface CurrentProvider {
    suspend fun nearestStation(location: GeoPoint): CurrentStationOutcome
}
