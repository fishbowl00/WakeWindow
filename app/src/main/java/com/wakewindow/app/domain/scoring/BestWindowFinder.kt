package com.wakewindow.app.domain.scoring

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Finds the longest contiguous span of GOOD-or-better points, breaking ties by highest
 * average score. See docs/MARINE_SCORING.md "Best Window." Operates purely on whatever
 * [PointAssessment]s it's given - for Mode A that's currently bounded to the plan's own
 * departure/return window; extending this to scan a wider daylight range (so WakeWindow can
 * suggest a better window than the one the user asked about) only requires passing more
 * points in, not changing this algorithm.
 */
object BestWindowFinder {

    /** Tolerance for treating the found window as "the same as what was planned" - a few
     * minutes of rounding at either edge shouldn't make the UI claim a "better" window exists
     * when it's really the same span. */
    private val PLANNED_WINDOW_TOLERANCE = Duration.ofMinutes(20)

    fun find(points: List<PointAssessment>, plannedStart: Instant, plannedEnd: Instant): BestWindow? {
        val sorted = points.sortedBy { it.at }
        var bestStart = -1
        var bestEnd = -1
        var bestLength = 0
        var bestAvg = -1.0

        var i = 0
        while (i < sorted.size) {
            if (sorted[i].category.severityRank <= BoatingCategory.GOOD.severityRank) {
                var j = i
                while (j < sorted.size && sorted[j].category.severityRank <= BoatingCategory.GOOD.severityRank) {
                    j++
                }
                val length = j - i
                val avg = sorted.subList(i, j).map { it.score }.average()
                if (length > bestLength || (length == bestLength && avg > bestAvg)) {
                    bestStart = i
                    bestEnd = j - 1
                    bestLength = length
                    bestAvg = avg
                }
                i = j
            } else {
                i++
            }
        }

        if (bestStart == -1) return null

        val windowPoints = sorted.subList(bestStart, bestEnd + 1)
        val start = windowPoints.first().at
        val end = windowPoints.last().at

        val deteriorationPoint = sorted.getOrNull(bestEnd + 1)
        val recommendReturnBy = if (deteriorationPoint != null && end.isBefore(plannedEnd)) end else null

        return BestWindow(
            start = start,
            end = end,
            averageScore = bestAvg.let { kotlin.math.round(it).toInt() },
            reasons = buildReasons(windowPoints, deteriorationPoint),
            matchesPlannedWindow = Duration.between(plannedStart, start).abs() <= PLANNED_WINDOW_TOLERANCE &&
                Duration.between(plannedEnd, end).abs() <= PLANNED_WINDOW_TOLERANCE,
            recommendReturnBy = recommendReturnBy,
        )
    }

    /** Deterministic explanation bullets generated from the actual scored data - never
     * hand-written/LLM prose. Describes the stable favorable conditions inside the window,
     * then whatever specifically changes at the point right after it (if any). */
    private fun buildReasons(windowPoints: List<PointAssessment>, deteriorationPoint: PointAssessment?): List<String> {
        val reasons = mutableListOf<String>()

        val maxWind = windowPoints.mapNotNull { it.conditions?.gustKts ?: it.conditions?.sustainedWindKts }.maxOrNull()
        if (maxWind != null) reasons += "Wind stays at or below ${maxWind.roundToInt()} kt"

        val waveHeights = windowPoints.mapNotNull { it.conditions?.waveHeightFt }
        if (waveHeights.isNotEmpty()) {
            // Compare at display precision, not raw doubles - two hours that both round to
            // "0.3 ft" but differ at the fourth decimal (a real artifact of unit conversion)
            // must never render as the redundant-looking "0.3 ft to 0.3 ft."
            val minText = formatFeet(waveHeights.min())
            val maxText = formatFeet(waveHeights.max())
            reasons += if (minText == maxText) "Seas stay near $minText" else "Seas stay $minText to $maxText"
        }

        if (deteriorationPoint != null) {
            val hazard = deteriorationPoint.hazards.firstOrNull()
            if (hazard != null) {
                reasons += "${hazard.message} after that"
            } else if (deteriorationPoint.category.severityRank > BoatingCategory.GOOD.severityRank) {
                reasons += "Conditions turn to ${deteriorationPoint.category.name.lowercase()} after that"
            }
        }

        return reasons
    }

    private fun formatFeet(value: Double): String = "${String.format("%.1f", value)} ft"
}
