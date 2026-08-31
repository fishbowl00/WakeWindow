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
 *
 * [TideTrend] is deliberately never left as a bare null when real tide events exist for the
 * water body - see docs/MARINE_SCORING.md "Tide trend." A `null` [MarineConditions.tideTrend]
 * means one specific thing: no tidal data applies here at all (an empty [events] list, e.g. a
 * non-tidal inland lake). Whenever [events] is non-empty, every hour resolves to an explicit
 * [TideTrend] value, including [TideTrend.UNKNOWN] for the rare case of a requested hour
 * falling outside the fetched window's bracketing events - the earlier bug this file fixes was
 * exactly that edge case silently producing `null` instead of an explicit state.
 */
object TideTimeline {

    /** How close to a charted high/low counts as "near" it rather than genuinely rising or
     * falling - roughly the flattest part of the tide curve around each extreme. */
    private val NEAR_EXTREME_THRESHOLD: Duration = Duration.ofMinutes(45)

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
            val trend = if (sorted.isEmpty()) null else trendAt(before, after, hour)
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
                else if (sorted.isEmpty()) Confidence.high()
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

    /** Always returns an explicit [TideTrend] - see the class doc. [at] is needed (not just
     * the bracketing events) to tell "rising, still 3 hours from high" apart from "essentially
     * at high water right now." */
    private fun trendAt(before: TideEvent?, after: TideEvent?, at: Instant): TideTrend {
        if (before == null && after == null) return TideTrend.UNKNOWN

        if (before == null && after != null) {
            // Only the window's very first event is known - infer direction from it alone.
            return if (Duration.between(at, after.time) <= NEAR_EXTREME_THRESHOLD) nearTrendOf(after.type) else directionTowards(after.type)
        }
        if (after == null && before != null) {
            // Only the window's very last event is known - the tide is moving away from it.
            return if (Duration.between(before.time, at) <= NEAR_EXTREME_THRESHOLD) nearTrendOf(before.type) else directionAwayFrom(before.type)
        }

        // Both known - this is the normal, fully-bracketed case.
        before!!
        after!!
        if (Duration.between(before.time, at) <= NEAR_EXTREME_THRESHOLD) return nearTrendOf(before.type)
        if (Duration.between(at, after.time) <= NEAR_EXTREME_THRESHOLD) return nearTrendOf(after.type)
        return when {
            before.type == TideEventType.LOW && after.type == TideEventType.HIGH -> TideTrend.RISING
            before.type == TideEventType.HIGH && after.type == TideEventType.LOW -> TideTrend.FALLING
            // Two consecutive same-type events shouldn't occur in real NOAA predictions, but
            // never fall through to a silent null if it somehow does.
            else -> TideTrend.UNKNOWN
        }
    }

    private fun nearTrendOf(type: TideEventType): TideTrend =
        if (type == TideEventType.HIGH) TideTrend.NEAR_HIGH else TideTrend.NEAR_LOW

    /** The tide is heading toward this upcoming extreme. */
    private fun directionTowards(type: TideEventType): TideTrend =
        if (type == TideEventType.HIGH) TideTrend.RISING else TideTrend.FALLING

    /** The tide just left this extreme behind. */
    private fun directionAwayFrom(type: TideEventType): TideTrend =
        if (type == TideEventType.HIGH) TideTrend.FALLING else TideTrend.RISING
}
