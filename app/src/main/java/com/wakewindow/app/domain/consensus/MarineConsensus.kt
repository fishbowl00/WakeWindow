package com.wakewindow.app.domain.consensus

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Merges multiple providers' readings for the same nominal timestamp/location into one
 * [MarineConditions]. Follows RideCast's own consensus philosophy (see
 * docs/RIDECAST_REFERENCE_AUDIT.md section 1/6): numeric fields average, percentage-risk
 * fields take the worse (higher) of the two rather than diluting a real signal, direction
 * fields use a circular (vector) mean rather than a naive average, and alerts are unioned,
 * never dropped. A single-provider reading passes through with reduced confidence rather
 * than being rejected - some data beats none.
 */
object MarineConsensus {

    fun merge(readings: List<MarineConditions>): MarineConditions? {
        if (readings.isEmpty()) return null
        if (readings.size == 1) return readings.single().withConsensusConfidence(1)

        val timestamp: Instant = readings.first().timestamp
        val location: GeoPoint = readings.first().location

        val allAlerts: List<MarineAlert> = readings.flatMap { it.marineAlerts }.distinctBy { it.id }

        val merged = MarineConditions(
            timestamp = timestamp,
            location = location,
            sustainedWindKts = average(readings.map { it.sustainedWindKts }),
            windDirectionDeg = circularMean(
                readings.mapNotNull { c -> c.windDirectionDeg?.let { it to (c.sustainedWindKts ?: 1.0) } },
            ),
            gustKts = average(readings.map { it.gustKts }),
            precipitationProbabilityPercent = maxOfNullable(readings.map { it.precipitationProbabilityPercent }),
            thunderstormProbabilityPercent = maxOfNullable(readings.map { it.thunderstormProbabilityPercent }),
            visibilityNm = readings.mapNotNull { it.visibilityNm }.minOrNull(), // worst (lowest) visibility wins
            airTemperatureF = average(readings.map { it.airTemperatureF }),
            apparentTemperatureF = average(readings.map { it.apparentTemperatureF }),
            waterTemperatureF = average(readings.map { it.waterTemperatureF }),
            waveHeightFt = average(readings.map { it.waveHeightFt }),
            waveDirectionDeg = circularMean(readings.mapNotNull { c -> c.waveDirectionDeg?.let { it to 1.0 } }),
            wavePeriodSec = average(readings.map { it.wavePeriodSec }),
            swellHeightFt = average(readings.map { it.swellHeightFt }),
            swellDirectionDeg = circularMean(readings.mapNotNull { c -> c.swellDirectionDeg?.let { it to 1.0 } }),
            swellPeriodSec = average(readings.map { it.swellPeriodSec }),
            tideHeightFt = firstNonNull(readings.map { it.tideHeightFt }),
            tideTrend = readings.firstNotNullOfOrNull { it.tideTrend },
            nextHighTide = readings.firstNotNullOfOrNull { it.nextHighTide },
            nextLowTide = readings.firstNotNullOfOrNull { it.nextLowTide },
            currentSpeedKts = firstNonNull(readings.map { it.currentSpeedKts }),
            currentDirectionDeg = firstNonNull(readings.map { it.currentDirectionDeg }),
            marineAlerts = allAlerts,
            source = mergedSource(readings.map { it.source }),
            observationAgeMinutes = readings.mapNotNull { it.observationAgeMinutes }.minOrNull(),
            confidence = Confidence.high(), // overwritten below by withConsensusConfidence semantics
        )
        return merged.withConsensusConfidence(readings.size)
    }

    private fun MarineConditions.withConsensusConfidence(providerCount: Int): MarineConditions {
        val level = if (providerCount >= 2) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
        val reasons = if (providerCount >= 2) emptyList() else listOf("Only one data source available for this hour")
        return copy(confidence = Confidence(level, reasons))
    }

    private fun average(values: List<Double?>): Double? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        return present.sum() / present.size
    }

    private fun maxOfNullable(values: List<Int?>): Int? = values.filterNotNull().maxOrNull()

    private fun firstNonNull(values: List<Double?>): Double? = values.firstOrNull { it != null }

    /** Vector (circular) mean of angles in degrees, each optionally weighted. */
    private fun circularMean(weighted: List<Pair<Double, Double>>): Double? {
        if (weighted.isEmpty()) return null
        var sumSin = 0.0
        var sumCos = 0.0
        for ((degrees, weight) in weighted) {
            val radians = Math.toRadians(degrees)
            sumSin += sin(radians) * weight
            sumCos += cos(radians) * weight
        }
        if (sumSin == 0.0 && sumCos == 0.0) return null
        val meanRadians = atan2(sumSin, sumCos)
        val meanDegrees = Math.toDegrees(meanRadians)
        return ((meanDegrees.roundToInt() + 360) % 360).toDouble()
    }

    private fun mergedSource(sources: List<SourceReference>): SourceReference {
        val names = sources.map { it.sourceName }.distinct().sorted().joinToString(" + ")
        return sources.first().copy(sourceName = names, sourceUrl = null)
    }
}
