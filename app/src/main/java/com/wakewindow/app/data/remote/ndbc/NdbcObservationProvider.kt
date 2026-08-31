package com.wakewindow.app.data.remote.ndbc

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.observation.MarineObservationOutcome
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.observation.SelectedMarineStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * NOAA NDBC observation provider, backed by `latest_obs.txt` - one plain-text snapshot of
 * every currently-reporting station network-wide (buoys and coastal C-MAN stations alike).
 * Not JSON, so this owns a raw OkHttp call rather than a Retrofit+serialization service - see
 * docs/DATA_SOURCES.md. The parsed snapshot is cached briefly in memory: it's a ~100KB
 * network-wide file that changes on NDBC's own reporting cadence (tens of minutes), so
 * re-fetching it once per screen recomposition would be wasteful - see docs/ROADMAP.md
 * "Scale and provider risk."
 */
class NdbcObservationProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val stationDirectory: NdbcStationDirectory = NdbcStationDirectory(client),
) : MarineObservationProvider {

    override val providerName: String = "NOAA National Data Buoy Center"

    private val cacheMutex = Mutex()
    private var cachedRows: List<NdbcObservationRow>? = null
    private var cachedAt: Instant? = null
    private val cacheTtl = Duration.ofMinutes(10)

    private suspend fun rows(): List<NdbcObservationRow> = cacheMutex.withLock {
        val cachedAtSnapshot = cachedAt
        val cached = cachedRows
        if (cached != null && cachedAtSnapshot != null && Duration.between(cachedAtSnapshot, Instant.now()) < cacheTtl) {
            return@withLock cached
        }
        val body = fetchBody()
        val parsed = NdbcObservationParser.parse(body)
        cachedRows = parsed
        cachedAt = Instant.now()
        parsed
    }

    private suspend fun fetchBody(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_OBS_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NDBC latest_obs.txt request failed (HTTP ${response.code})")
            response.body?.string() ?: throw IOException("NDBC latest_obs.txt returned an empty body")
        }
    }

    override suspend fun nearestObservation(location: GeoPoint): MarineObservationOutcome =
        try {
            val allRows = rows()
            val now = Instant.now()
            val candidate = NdbcStationSelector.select(allRows, location, now)
                ?: return MarineObservationOutcome.NoStationAvailable

            val displayName = stationDirectory.nameFor(candidate.row.stationId) ?: candidate.row.stationId
            val source = SourceReference(
                sourceName = providerName,
                sourceUrl = "https://www.ndbc.noaa.gov/station_page.php?station=${candidate.row.stationId}",
                retrievedAt = now,
                stationId = candidate.row.stationId,
                stationName = displayName,
                stationDistanceNm = candidate.distanceNm,
            )
            val conditions = NdbcMapper.toMarineConditions(candidate.row, location, now, source, candidate.freshness)
            val station = SelectedMarineStation(
                stationId = candidate.row.stationId,
                name = displayName,
                location = candidate.row.location,
                distanceNm = candidate.distanceNm,
                observedAt = candidate.row.observedAt,
                ageMinutes = Duration.between(candidate.row.observedAt, now).toMinutes(),
                freshness = candidate.freshness,
                hasWindData = candidate.row.hasWindData,
                hasWaveData = candidate.row.hasWaveData,
                selectionReason = NdbcStationSelector.selectionReason(candidate),
            )
            MarineObservationOutcome.Success(station, conditions)
        } catch (e: IOException) {
            MarineObservationOutcome.Failure("Network error contacting NDBC", e)
        }

    companion object {
        private const val LATEST_OBS_URL = "https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt"
    }
}
