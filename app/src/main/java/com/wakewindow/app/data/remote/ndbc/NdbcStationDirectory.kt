package com.wakewindow.app.data.remote.ndbc

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
 * Best-effort station-id -> human-readable-name lookup from NDBC's pipe-delimited
 * `station_table.txt` (`# STATION_ID | OWNER | TTYPE | HULL | NAME | ...`). Kept entirely
 * separate from [NdbcObservationParser]/[NdbcObservationProvider] - a name lookup failure must
 * never block getting the actual observation, so callers fall back to the bare station id.
 */
class NdbcStationDirectory(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val cacheMutex = Mutex()
    private var cachedNames: Map<String, String>? = null
    private var cachedAt: Instant? = null
    private val cacheTtl = Duration.ofHours(24) // station names essentially never change

    suspend fun nameFor(stationId: String): String? =
        runCatching { names()[stationId] }.getOrNull()

    private suspend fun names(): Map<String, String> = cacheMutex.withLock {
        val cachedAtSnapshot = cachedAt
        val cached = cachedNames
        if (cached != null && cachedAtSnapshot != null && Duration.between(cachedAtSnapshot, Instant.now()) < cacheTtl) {
            return@withLock cached
        }
        val body = fetchBody()
        val parsed = parse(body)
        cachedNames = parsed
        cachedAt = Instant.now()
        parsed
    }

    private suspend fun fetchBody(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(STATION_TABLE_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NDBC station_table.txt request failed (HTTP ${response.code})")
            response.body?.string() ?: throw IOException("NDBC station_table.txt returned an empty body")
        }
    }

    private fun parse(body: String): Map<String, String> =
        body.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val fields = line.split("|")
                if (fields.size < 5) return@mapNotNull null
                val id = fields[0].trim()
                val name = fields[4].trim()
                if (id.isEmpty() || name.isEmpty()) null else id to name
            }
            .toMap()

    companion object {
        private const val STATION_TABLE_URL = "https://www.ndbc.noaa.gov/data/stations/station_table.txt"
    }
}
