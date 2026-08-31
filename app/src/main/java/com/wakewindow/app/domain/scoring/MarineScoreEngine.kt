package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.observation.WaterEnvironment
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
        /** A hazard-shaped adjustment derived from a fresh, representative station observation
         * that disagrees with the forecast-at-that-station - see [ObservationalCautionEvaluator].
         * Applied only to the departure point; never blended into any forecast value. Null when
         * no such caution applies (no observation, not representative, not near-term, or the
         * observation isn't materially worse than forecast). */
        observationalCaution: Hazard? = null,
        /** The launch's classified water body - see [WaterEnvironment] and
         * [EvidenceRequirementEvaluator]. Defaults to UNKNOWN, which never gates any category
         * (an unclassified environment is treated as "don't know," not "assume the worst case"). */
        environment: WaterEnvironment = WaterEnvironment.UNKNOWN,
    ): BoatingWindowAssessment {
        require(samples.isNotEmpty()) { "assess() requires at least one route sample" }

        val rawPoints = samples.map { sample -> MarinePointScorer.score(sample, conditionsFor(sample), vessel, environment) }
        val points = if (observationalCaution == null) {
            rawPoints
        } else {
            rawPoints.map { point ->
                if (point.sample.role == RouteSampleRole.DEPARTURE) applyObservationalCaution(point, observationalCaution) else point
            }
        }

        val departure = points.firstOrNull { it.sample.role == RouteSampleRole.DEPARTURE } ?: points.first()
        val returnPoint = points.lastOrNull { it.sample.role == RouteSampleRole.RETURN } ?: points.last()
        val underway = points.filter {
            it.sample.role == RouteSampleRole.UNDERWAY || it.sample.role == RouteSampleRole.WAYPOINT
        }

        val overall = computeOverall(departure, underway, returnPoint)
        val bestWindow = BestWindowFinder.find(points, departure.at, returnPoint.at)
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

    /** The cap can only pull the category down, exactly like every other gate in
     * [MarinePointScorer] - never up from what the forecast-based score already implied. */
    private fun applyObservationalCaution(point: PointAssessment, hazard: Hazard): PointAssessment {
        val cap = hazard.categoryCap ?: return point
        return point.copy(
            category = worstCategory(point.category, cap),
            hazards = point.hazards + hazard,
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

    /** A [HazardType] whose [Hazard.message] doesn't vary by hour - a marine alert stays
     * active with the identical text at every hour it covers, unlike e.g. a wave-height gate
     * whose message genuinely differs hour to hour (a different reading each time). Deduping
     * these by message (not by hour) turns "the same alert, repeated once per sampled hour"
     * into the single ongoing concern it actually is - discovered during Sprint 3's live
     * Clinton Lake re-validation, where an active Heat Advisory appeared nine times in
     * [BoatingWindowAssessment.worstHazards] for one nine-hour outing. See
     * docs/ASSESSMENT_VALIDATION.md. */
    private val MESSAGE_STABLE_HAZARD_TYPES = setOf(HazardType.MARINE_ALERT_ADVISORY, HazardType.MARINE_ALERT_SEVERE, HazardType.MARINE_ALERT_EXTREME)

    private fun rankHazards(points: List<PointAssessment>, returnAt: java.time.Instant): List<Hazard> =
        points.flatMap { it.hazards }
            .distinctBy { hazard ->
                if (hazard.type in MESSAGE_STABLE_HAZARD_TYPES) "${hazard.type}-${hazard.message}" else "${hazard.type}-${hazard.at}"
            }
            .sortedByDescending { hazardSeverityScore(it) * returnProximityWeight(it.at, returnAt) }
}
