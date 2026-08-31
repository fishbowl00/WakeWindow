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
 * Turns a day's flood-max/ebb-max/slack current predictions into per-hour speed/direction
 * values, using the same cosine-bell approximation between consecutive turns that
 * [TideTimeline] uses between tide extremes - a reasonable approximation of the real curve,
 * not a claim of precision beyond what a station-based prediction already is. See
 * docs/DATA_SOURCES.md "Current predictions."
 */
object CurrentTimeline {

    fun conditionsAt(
        events: List<CurrentEvent>,
        hours: List<Instant>,
        location: GeoPoint,
        source: SourceReference,
    ): List<MarineConditions> {
        val sorted = events.sortedBy { it.time }
        return hours.map { hour ->
            val (before, after) = bracket(sorted, hour)
            val (speed, direction) = interpolate(before, after, hour)
            val next = sorted.firstOrNull { it.time.isAfter(hour) }

            MarineConditions(
                timestamp = hour,
                location = location,
                currentSpeedKts = speed,
                currentDirectionDeg = direction,
                nextCurrentEvent = next,
                source = source,
                confidence = if (before != null && after != null) Confidence.high()
                else if (sorted.isEmpty()) Confidence.high()
                else Confidence(ConfidenceLevel.MEDIUM, listOf("Current prediction extends beyond this station's known events for this window")),
            )
        }
    }

    private fun bracket(sorted: List<CurrentEvent>, at: Instant): Pair<CurrentEvent?, CurrentEvent?> {
        val before = sorted.lastOrNull { !it.time.isAfter(at) }
        val after = sorted.firstOrNull { it.time.isAfter(at) }
        return before to after
    }

    private fun interpolate(before: CurrentEvent?, after: CurrentEvent?, at: Instant): Pair<Double?, Double?> {
        if (before == null && after == null) return null to null
        if (before == null) return after!!.speedKts to after.directionDeg
        if (after == null) return before.speedKts to before.directionDeg

        val totalSeconds = Duration.between(before.time, after.time).seconds.toDouble()
        if (totalSeconds <= 0.0) return before.speedKts to before.directionDeg
        val elapsedSeconds = Duration.between(before.time, at).seconds.toDouble()
        val fraction = (elapsedSeconds / totalSeconds).coerceIn(0.0, 1.0)
        val cosineFraction = (1 - cos(Math.PI * fraction)) / 2.0
        val speed = before.speedKts + (after.speedKts - before.speedKts) * cosineFraction

        // Direction is undefined at slack (zero speed) - use whichever bracketing turn has a
        // real direction; when both do (unusual - real MAX_SLACK data always alternates
        // max/slack), split at the midpoint rather than guessing further.
        val direction = when {
            before.directionDeg != null && after.directionDeg == null -> before.directionDeg
            before.directionDeg == null && after.directionDeg != null -> after.directionDeg
            before.directionDeg != null && after.directionDeg != null ->
                if (fraction < 0.5) before.directionDeg else after.directionDeg
            else -> null
        }
        return speed to direction
    }
}
