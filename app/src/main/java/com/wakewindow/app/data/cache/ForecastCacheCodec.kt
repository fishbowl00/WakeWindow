package com.wakewindow.app.data.cache

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Serializes an hourly [MarineConditions] series for [CachedGeneralWeatherProvider]/
 * [CachedMarineForecastProvider] - see docs/CACHE_POLICY.md "NWS/Open-Meteo forecast." Narrowed
 * to exactly the fields a *forecast* provider (NWS's `NwsMapper`, Open-Meteo's
 * `OpenMeteoMapper`) actually populates - never tide/current-event, marine-alert, or
 * observation-age fields, which belong to different providers entirely and are merged in later
 * by [com.wakewindow.app.data.repository.DefaultBoatingRepository]/
 * [com.wakewindow.app.data.repository.DefaultTripBoatingRepository], never by this cache. Same
 * narrow-DTO convention as `FwcFacilityCacheCodec`.
 */
@Serializable
private data class ConditionsDto(
    val timestampEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val sustainedWindKts: Double? = null,
    val windDirectionDeg: Double? = null,
    val gustKts: Double? = null,
    val precipitationProbabilityPercent: Int? = null,
    val thunderstormProbabilityPercent: Int? = null,
    val visibilityNm: Double? = null,
    val airTemperatureF: Double? = null,
    val apparentTemperatureF: Double? = null,
    val waterTemperatureF: Double? = null,
    val waveHeightFt: Double? = null,
    val waveDirectionDeg: Double? = null,
    val wavePeriodSec: Double? = null,
    val swellHeightFt: Double? = null,
    val swellDirectionDeg: Double? = null,
    val swellPeriodSec: Double? = null,
    val currentSpeedKts: Double? = null,
    val currentDirectionDeg: Double? = null,
    val sourceName: String,
    val sourceUrl: String? = null,
    val retrievedAtEpochMillis: Long,
    val confidenceLevel: String,
    val confidenceReasons: List<String>,
)

object ForecastCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(readings: List<MarineConditions>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ConditionsDto.serializer()),
            readings.map { c ->
                ConditionsDto(
                    timestampEpochMillis = c.timestamp.toEpochMilli(),
                    latitude = c.location.latitude,
                    longitude = c.location.longitude,
                    sustainedWindKts = c.sustainedWindKts,
                    windDirectionDeg = c.windDirectionDeg,
                    gustKts = c.gustKts,
                    precipitationProbabilityPercent = c.precipitationProbabilityPercent,
                    thunderstormProbabilityPercent = c.thunderstormProbabilityPercent,
                    visibilityNm = c.visibilityNm,
                    airTemperatureF = c.airTemperatureF,
                    apparentTemperatureF = c.apparentTemperatureF,
                    waterTemperatureF = c.waterTemperatureF,
                    waveHeightFt = c.waveHeightFt,
                    waveDirectionDeg = c.waveDirectionDeg,
                    wavePeriodSec = c.wavePeriodSec,
                    swellHeightFt = c.swellHeightFt,
                    swellDirectionDeg = c.swellDirectionDeg,
                    swellPeriodSec = c.swellPeriodSec,
                    currentSpeedKts = c.currentSpeedKts,
                    currentDirectionDeg = c.currentDirectionDeg,
                    sourceName = c.source.sourceName,
                    sourceUrl = c.source.sourceUrl,
                    retrievedAtEpochMillis = c.source.retrievedAt.toEpochMilli(),
                    confidenceLevel = c.confidence.level.name,
                    confidenceReasons = c.confidence.reasons,
                )
            },
        )

    fun decode(payload: String): List<MarineConditions> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ConditionsDto.serializer()), payload).map { dto ->
            MarineConditions(
                timestamp = Instant.ofEpochMilli(dto.timestampEpochMillis),
                location = GeoPoint(dto.latitude, dto.longitude),
                sustainedWindKts = dto.sustainedWindKts,
                windDirectionDeg = dto.windDirectionDeg,
                gustKts = dto.gustKts,
                precipitationProbabilityPercent = dto.precipitationProbabilityPercent,
                thunderstormProbabilityPercent = dto.thunderstormProbabilityPercent,
                visibilityNm = dto.visibilityNm,
                airTemperatureF = dto.airTemperatureF,
                apparentTemperatureF = dto.apparentTemperatureF,
                waterTemperatureF = dto.waterTemperatureF,
                waveHeightFt = dto.waveHeightFt,
                waveDirectionDeg = dto.waveDirectionDeg,
                wavePeriodSec = dto.wavePeriodSec,
                swellHeightFt = dto.swellHeightFt,
                swellDirectionDeg = dto.swellDirectionDeg,
                swellPeriodSec = dto.swellPeriodSec,
                currentSpeedKts = dto.currentSpeedKts,
                currentDirectionDeg = dto.currentDirectionDeg,
                source = SourceReference(
                    sourceName = dto.sourceName,
                    sourceUrl = dto.sourceUrl,
                    retrievedAt = Instant.ofEpochMilli(dto.retrievedAtEpochMillis),
                ),
                confidence = Confidence(
                    level = runCatching { ConfidenceLevel.valueOf(dto.confidenceLevel) }.getOrDefault(ConfidenceLevel.MEDIUM),
                    reasons = dto.confidenceReasons,
                ),
            )
        }
}
