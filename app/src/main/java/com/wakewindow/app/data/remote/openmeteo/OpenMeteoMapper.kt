package com.wakewindow.app.data.remote.openmeteo

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.model.UnitConversions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

object OpenMeteoMapper {

    /** Open-Meteo's `timezone=UTC` hourly timestamps are naive ("2026-08-30T00:00", no
     * offset) - always UTC when requested this way. */
    private fun parseUtcNaive(text: String): Instant =
        LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)

    fun mapGeneral(response: OpenMeteoForecastResponse, location: GeoPoint, source: SourceReference): List<MarineConditions> {
        val hourly = response.hourly ?: return emptyList()
        return hourly.time.indices.map { i ->
            MarineConditions(
                timestamp = parseUtcNaive(hourly.time[i]),
                location = location,
                sustainedWindKts = hourly.wind_speed_10m?.getOrNull(i),
                windDirectionDeg = hourly.wind_direction_10m?.getOrNull(i),
                gustKts = hourly.wind_gusts_10m?.getOrNull(i),
                precipitationProbabilityPercent = hourly.precipitation_probability?.getOrNull(i),
                airTemperatureF = hourly.temperature_2m?.getOrNull(i),
                apparentTemperatureF = hourly.apparent_temperature?.getOrNull(i),
                visibilityNm = hourly.visibility?.getOrNull(i)?.let { it / 1852.0 },
                source = source,
                confidence = Confidence(ConfidenceLevel.MEDIUM, listOf("Development-only provider - see docs/DATA_SOURCES.md")),
            )
        }
    }

    fun mapMarine(response: OpenMeteoMarineResponse, location: GeoPoint, source: SourceReference): List<MarineConditions> {
        val hourly = response.hourly ?: return emptyList()
        return hourly.time.indices.map { i ->
            MarineConditions(
                timestamp = parseUtcNaive(hourly.time[i]),
                location = location,
                waveHeightFt = hourly.wave_height?.getOrNull(i)?.let { UnitConversions.metersToFeet(it) },
                waveDirectionDeg = hourly.wave_direction?.getOrNull(i),
                wavePeriodSec = hourly.wave_period?.getOrNull(i),
                swellHeightFt = hourly.swell_wave_height?.getOrNull(i)?.let { UnitConversions.metersToFeet(it) },
                swellDirectionDeg = hourly.swell_wave_direction?.getOrNull(i),
                swellPeriodSec = hourly.swell_wave_period?.getOrNull(i),
                waterTemperatureF = hourly.sea_surface_temperature?.getOrNull(i)?.let { UnitConversions.celsiusToFahrenheit(it) },
                currentSpeedKts = hourly.ocean_current_velocity?.getOrNull(i)?.let { UnitConversions.kmhToKnots(it) },
                currentDirectionDeg = hourly.ocean_current_direction?.getOrNull(i),
                source = source,
                confidence = Confidence(ConfidenceLevel.MEDIUM, listOf("Development-only provider - see docs/DATA_SOURCES.md")),
            )
        }
    }
}
