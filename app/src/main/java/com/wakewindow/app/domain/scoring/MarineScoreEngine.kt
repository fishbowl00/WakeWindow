package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Entry point for turning a set of [RouteSample]s + their forecast conditions into a
 * [BoatingWindowAssessment]. See docs/MARINE_SCORING.md for the full algorithm; this file is
 * the literal implementation of "Window-level aggregation - why return conditions dominate."
 */
object MarineScoreEngine {

    fun assess(
        samples: List<RouteSample>,
        conditionsFor: (RouteSample) -> MarineConditions?,
        vessel: VesselProfile,
    ): BoatingWindowAssessment {
        require(samples.isNotEmpty()) { "assess() requires at least one route sample" }

        val points = samples.map { sample -> MarinePointScorer.score(sample, conditionsFor(sample), vessel) }

        val departure = points.firstOrNull { it.sample.role == RouteSampleRole.DEPARTURE } ?: points.first()
        val returnPoint = points.lastOrNull { it.sample.role == RouteSampleRole.RETURN } ?: points.last()
        val underway = points.filter {
            it.sample.role == RouteSampleRole.UNDERWAY || it.sample.role == RouteSampleRole.WAYPOINT
        }

        val overall = computeOverall(departure, underway, returnPoint)
        val bestWindow = BestWindowFinder.find(points)
        val worstHazards = rankHazards(points, returnPoint.at)
        val confidence = points.map { it.confidence }.reduceOrNull { a, b -> a.worstOf(b) }
            ?: departure.confidence

        return BoatingWindowAssessment(
            departureAssessment = departure,
            underwayAssessments = underway,
            returnAssessment = returnPoint,
            overallAssessment = overall,
            bestWindow = bestWindow,
            worstHazards = worstHazards,
            confidence = confidence,
        )
    }

    private fun computeOverall(
        departure: PointAssessment,
        underway: List<PointAssessment>,
        returnPoint: PointAssessment,
    ): OverallAssessment {
        val worstUnderway = underway.maxByOrNull { it.category.severityRank }
        val category = listOfNotNull(departure.category, worstUnderway?.category, returnPoint.category)
            .reduce(::worstCategory)

        val underwayAvg = if (underway.isEmpty()) departure.score.toDouble() else underway.map { it.score }.average()
        val allPoints = listOf(departure) + underway + returnPoint
        val worstHazardPenalty = worstHazardPenalty(allPoints, returnPoint.at)

        val blendedScore = (
            0.20 * departure.score +
                0.30 * underwayAvg +
                0.35 * returnPoint.score +
                0.15 * (100 - worstHazardPenalty)
            ).roundToInt().coerceIn(0, 100)

        val reasons = rankHazards(allPoints, returnPoint.at).take(4)

        return OverallAssessment(category = category, score = blendedScore, reasons = reasons)
    }

    /** A hazard's severity, scaled down the further its hour is from the planned return -
     * the mechanism that makes a storm timed near return dominate the score even when
     * earlier hours were fine. See docs/MARINE_SCORING.md point 2. */
    private fun worstHazardPenalty(points: List<PointAssessment>, returnAt: java.time.Instant): Double {
        val hazards = points.flatMap { it.hazards }
        if (hazards.isEmpty()) return 0.0
        return hazards.maxOf { hazard ->
            val severity = hazardSeverityScore(hazard)
            severity * returnProximityWeight(hazard.at, returnAt)
        }
    }

    private fun hazardSeverityScore(hazard: Hazard): Double = when (hazard.categoryCap) {
        BoatingCategory.NO_GO -> 100.0
        BoatingCategory.POOR -> 60.0
        BoatingCategory.CAUTION -> 30.0
        else -> 10.0
    }

    private fun returnProximityWeight(at: java.time.Instant, returnAt: java.time.Instant): Double {
        val hoursDiff = kotlin.math.abs(Duration.between(at, returnAt).toMinutes()) / 60.0
        return (1.0 / (1.0 + hoursDiff / 3.0)).coerceIn(0.3, 1.0)
    }

    private fun rankHazards(points: List<PointAssessment>, returnAt: java.time.Instant): List<Hazard> =
        points.flatMap { it.hazards }
            .distinctBy { "${it.type}-${it.at}" }
            .sortedByDescending { hazardSeverityScore(it) * returnProximityWeight(it.at, returnAt) }
}
