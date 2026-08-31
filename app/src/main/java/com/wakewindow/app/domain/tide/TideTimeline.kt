package com.wakewindow.app.domain.tide

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import java.time.Duration
import java.time.Instant
import kotlin.math.cos

/**
 * Turns a day's high/low tide predictions into per-hour tide height/trend/next-event values,
 * using the standard mariner's cosine-bell approximation between consecutive extremes (not a
 * true harmonic curve, but a reasonable approximation - clearly a prediction either way, not
 * a claim of precision beyond what a station-based prediction already is).
 */
object TideTimeline {

    fun conditionsAt(
        events: List<TideEvent>,
        hours: List<Instant>,
        location: GeoPoint,
        source: SourceReference,
    ): List<MarineConditions> {
        val sorted = events.sortedBy { it.time }
        return hours.map { hour ->
            val (before, after) = bracket(sorted, hour)
            val height = interpolateHeight(before, after, hour)
            val trend = trendAt(before, after)
            val nextHigh = sorted.firstOrNull { it.type == TideEventType.HIGH && it.time.isAfter(hour) }
            val nextLow = sorted.firstOrNull { it.type == TideEventType.LOW && it.time.isAfter(hour) }

            MarineConditions(
                timestamp = hour,
                location = location,
                tideHeightFt = height,
                tideTrend = trend,
                nextHighTide = nextHigh,
                nextLowTide = nextLow,
                source = source,
                confidence = if (before != null && after != null) Confidence.high()
                else Confidence(ConfidenceLevel.MEDIUM, listOf("Tide prediction extends beyond this station's known events for this window")),
            )
        }
    }

    private fun bracket(sorted: List<TideEvent>, at: Instant): Pair<TideEvent?, TideEvent?> {
        val before = sorted.lastOrNull { !it.time.isAfter(at) }
        val after = sorted.firstOrNull { it.time.isAfter(at) }
        return before to after
    }

    private fun interpolateHeight(before: TideEvent?, after: TideEvent?, at: Instant): Double? {
        if (before == null && after == null) return null
        if (before == null) return after?.heightFt
        if (after == null) return before.heightFt
        val totalSeconds = Duration.between(before.time, after.time).seconds.toDouble()
        if (totalSeconds <= 0.0) return before.heightFt
        val elapsedSeconds = Duration.between(before.time, at).seconds.toDouble()
        val fraction = (elapsedSeconds / totalSeconds).coerceIn(0.0, 1.0)
        val cosineFraction = (1 - cos(Math.PI * fraction)) / 2.0
        return before.heightFt + (after.heightFt - before.heightFt) * cosineFraction
    }

    private fun trendAt(before: TideEvent?, after: TideEvent?): TideTrend? {
        if (before == null || after == null) return null
        return when {
            before.type == TideEventType.LOW && after.type == TideEventType.HIGH -> TideTrend.RISING
            before.type == TideEventType.HIGH && after.type == TideEventType.LOW -> TideTrend.FALLING
            else -> null
        }
    }
}
