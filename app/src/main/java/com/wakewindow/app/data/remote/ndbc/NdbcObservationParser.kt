package com.wakewindow.app.data.remote.ndbc

import com.wakewindow.app.domain.model.GeoPoint
import java.time.Instant

/**
 * One parsed row from NDBC's `latest_obs.txt` - all currently-reporting stations network-wide
 * in one fixed-column text file (STN LAT LON YYYY MM DD hh mm WDIR WSPD GST WVHT DPD APD MWD
 * PRES PTDY ATMP WTMP DEWP VIS TIDE). "MM" is NDBC's literal missing-value marker - see
 * docs/DATA_SOURCES.md. All numeric fields here are still in NDBC's native units (m/s, meters,
 * degC); unit conversion happens in the mapper, not here, so this class is a faithful,
 * testable parse of the wire format.
 */
data class NdbcObservationRow(
    val stationId: String,
    val location: GeoPoint,
    val observedAt: Instant,
    val windDirectionDeg: Double?,
    val windSpeedMps: Double?,
    val gustMps: Double?,
    val waveHeightM: Double?,
    val dominantWavePeriodSec: Double?,
    val averageWavePeriodSec: Double?,
    val waveDirectionDeg: Double?,
    val pressureHpa: Double?,
    val airTempC: Double?,
    val waterTempC: Double?,
    val dewpointC: Double?,
    val visibilityNm: Double?,
) {
    val hasWindData: Boolean get() = windSpeedMps != null
    val hasWaveData: Boolean get() = waveHeightM != null
}

object NdbcObservationParser {

    private const val MISSING = "MM"
    private const val EXPECTED_COLUMNS = 22

    /** Parses the whole `latest_obs.txt` body. A malformed or short line is skipped, not
     * fatal - one bad row (a mid-file header repeat, a truncated download, a future NDBC
     * column addition) must never take down every other station's data. */
    fun parse(body: String): List<NdbcObservationRow> =
        body.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { runCatching { parseLine(it) }.getOrNull() }
            .toList()

    private fun parseLine(line: String): NdbcObservationRow? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size < EXPECTED_COLUMNS) return null

        val lat = tokens[1].toDoubleOrNull() ?: return null
        val lon = tokens[2].toDoubleOrNull() ?: return null
        val year = tokens[3].toIntOrNull() ?: return null
        val month = tokens[4].toIntOrNull() ?: return null
        val day = tokens[5].toIntOrNull() ?: return null
        val hour = tokens[6].toIntOrNull() ?: return null
        val minute = tokens[7].toIntOrNull() ?: return null

        val location = runCatching { GeoPoint(lat, lon) }.getOrNull() ?: return null
        val observedAt = runCatching {
            Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hour, minute))
        }.getOrNull() ?: return null

        return NdbcObservationRow(
            stationId = tokens[0],
            location = location,
            observedAt = observedAt,
            windDirectionDeg = tokens[8].toNullableDouble(),
            windSpeedMps = tokens[9].toNullableDouble(),
            gustMps = tokens[10].toNullableDouble(),
            waveHeightM = tokens[11].toNullableDouble(),
            dominantWavePeriodSec = tokens[12].toNullableDouble(),
            averageWavePeriodSec = tokens[13].toNullableDouble(),
            waveDirectionDeg = tokens[14].toNullableDouble(),
            pressureHpa = tokens[15].toNullableDouble(),
            airTempC = tokens[17].toNullableDouble(),
            waterTempC = tokens[18].toNullableDouble(),
            dewpointC = tokens[19].toNullableDouble(),
            visibilityNm = tokens[20].toNullableDouble(),
        )
    }

    private fun String.toNullableDouble(): Double? = if (this == MISSING) null else toDoubleOrNull()
}
