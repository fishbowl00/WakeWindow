package com.wakewindow.app.data.remote.ndbc

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.model.UnitConversions
import com.wakewindow.app.domain.observation.ObservationFreshness
import java.time.Duration
import java.time.Instant

object NdbcMapper {

    fun toMarineConditions(row: NdbcObservationRow, location: GeoPoint, now: Instant, source: SourceReference, freshness: ObservationFreshness): MarineConditions {
        val ageMinutes = Duration.between(row.observedAt, now).toMinutes().toInt().coerceAtLeast(0)
        val confidenceLevel = when (freshness) {
            ObservationFreshness.FRESH -> ConfidenceLevel.HIGH
            ObservationFreshness.AGING -> ConfidenceLevel.MEDIUM
            ObservationFreshness.STALE, ObservationFreshness.UNUSABLE -> ConfidenceLevel.LOW
        }
        val reasons = if (freshness == ObservationFreshness.STALE || freshness == ObservationFreshness.UNUSABLE) {
            listOf("Nearest buoy observation is $ageMinutes min old (${freshness.name.lowercase()})")
        } else {
            emptyList()
        }

        return MarineConditions(
            timestamp = row.observedAt,
            location = location,
            sustainedWindKts = row.windSpeedMps?.let { UnitConversions.mpsToKnots(it) },
            windDirectionDeg = row.windDirectionDeg,
            gustKts = row.gustMps?.let { UnitConversions.mpsToKnots(it) },
            visibilityNm = row.visibilityNm,
            airTemperatureF = row.airTempC?.let { UnitConversions.celsiusToFahrenheit(it) },
            waterTemperatureF = row.waterTempC?.let { UnitConversions.celsiusToFahrenheit(it) },
            waveHeightFt = row.waveHeightM?.let { UnitConversions.metersToFeet(it) },
            waveDirectionDeg = row.waveDirectionDeg,
            wavePeriodSec = row.dominantWavePeriodSec ?: row.averageWavePeriodSec,
            source = source,
            observationAgeMinutes = ageMinutes,
            confidence = Confidence(confidenceLevel, reasons),
        )
    }
}
