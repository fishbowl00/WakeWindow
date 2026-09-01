package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.scoring.worstCategory

/**
 * Combines already-scored [TripPointAssessment]s/[TripLegAssessment]s into the trip-level
 * [TripAssessment] - the worst-case-gating counterpart to
 * [com.wakewindow.app.domain.scoring.MarineScoreEngine.assess] for Mode B. Deliberately pure
 * (no I/O, no provider calls) so it's fully unit-testable with hand-built fakes - see
 * docs/TRIP_ASSESSMENT.md and the sprint brief's Phase 9/25 requirements ("one bad waypoint
 * gates trip," "calm endpoints but hazardous middle sample").
 *
 * Fetching the evidence each [TripPointAssessment] is built from lives in
 * [com.wakewindow.app.data.repository.DefaultTripBoatingRepository]; this object only combines
 * results that already exist.
 */
object TripAssessmentBuilder {

    fun build(
        plan: MarineTripPlan,
        timeline: List<TripPointAssessment>,
        legs: List<TripLegAssessment>,
        horizonWarning: String? = null,
        limitViolations: List<TripPlanLimitViolation> = emptyList(),
    ): TripAssessment {
        require(timeline.isNotEmpty()) { "build() requires at least one trip point (departure)" }

        val overallCategory = timeline.map { it.category }.reduce(::worstCategory)
        val worstHazards = TripHazardRanking.rank(timeline.flatMap { it.hazards })
        val confidence: Confidence = timeline.map { it.confidence }.reduce { a, b -> a.worstOf(b) }
        val mainConcern = buildMainConcern(timeline, worstHazards)

        return TripAssessment(
            plan = plan,
            timeline = timeline,
            legs = legs,
            overallCategory = overallCategory,
            worstHazards = worstHazards,
            confidence = confidence,
            mainConcern = mainConcern,
            horizonWarning = horizonWarning,
            limitViolations = limitViolations,
        )
    }

    /** Names the single worst hazard and the trip point it occurred at - deterministic, built
     * directly from already-scored data, never hand-written/LLM prose. Prefers a hazard's own
     * point name when it's a real user waypoint/departure/destination; a generated weather
     * sample is described by its role instead, since it has no name of its own. */
    private fun buildMainConcern(timeline: List<TripPointAssessment>, worstHazards: List<com.wakewindow.app.domain.scoring.Hazard>): String? {
        val worst = worstHazards.firstOrNull() ?: return null
        val point = timeline.filter { it.hazards.any { h -> h.type == worst.type && h.message == worst.message } }
            .minByOrNull { kotlin.math.abs(java.time.Duration.between(it.at, worst.at).toMillis()) }
            ?: return worst.message

        val location = when (point.kind) {
            TripPointKind.WEATHER_SAMPLE -> "a weather sample point along the route"
            TripPointKind.DEPARTURE -> "at departure"
            TripPointKind.DESTINATION -> point.name?.let { "near $it" } ?: "near the destination"
            TripPointKind.WAYPOINT -> point.name?.let { "near $it" } ?: "near a planning waypoint"
        }
        return "${worst.message} $location"
    }
}
