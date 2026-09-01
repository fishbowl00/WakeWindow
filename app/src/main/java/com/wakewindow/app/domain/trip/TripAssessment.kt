package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.observation.WaterEnvironment
import com.wakewindow.app.domain.scoring.BoatingCategory
import com.wakewindow.app.domain.scoring.Hazard
import com.wakewindow.app.domain.scoring.HazardType
import com.wakewindow.app.domain.scoring.PointAssessment
import com.wakewindow.app.domain.scoring.worstCategory
import com.wakewindow.app.domain.tide.CurrentStation
import com.wakewindow.app.domain.tide.TideStation

/**
 * What kind of point a [TripPointAssessment] is - kept distinct from the underlying
 * [com.wakewindow.app.domain.route.RouteSampleRole] because the UI-facing distinction that
 * matters here (docs/TRIP_PLANNING.md "Weather samples vs. planning waypoints") is exactly
 * "did the user choose this point," which [WEATHER_SAMPLE] vs [WAYPOINT] must never blur.
 */
enum class TripPointKind { DEPARTURE, WEATHER_SAMPLE, WAYPOINT, DESTINATION }

/**
 * One trip point's full assessment - the timed weather/tide/current/observation evidence at
 * that point's own location and expected arrival time, never a departure-hour value reused
 * across the whole trip. See docs/TRIP_ASSESSMENT.md.
 */
data class TripPointAssessment(
    val kind: TripPointKind,
    /** The user-supplied waypoint/departure/destination name - null for a generated
     * [TripPointKind.WEATHER_SAMPLE], which was never named by the user. */
    val name: String?,
    val point: PointAssessment,
    val waterEnvironment: WaterEnvironment = WaterEnvironment.UNKNOWN,
    val nearestTideStation: TideStation? = null,
    val nearestCurrentStation: CurrentStation? = null,
    val nearestObservationStation: SelectedMarineStation? = null,
    /** False when this point's expected arrival is far enough in the future that a live buoy
     * reading fetched "now" would say nothing meaningful about conditions when the boater
     * actually gets there - see docs/TRIP_ASSESSMENT.md "Observation relevance." When false,
     * [nearestObservationStation] is always null: an inapplicable observation is never fetched
     * at all, not fetched and then hidden. */
    val observationApplicable: Boolean = false,
) {
    val at get() = point.at
    val category: BoatingCategory get() = point.category
    val hazards: List<Hazard> get() = point.hazards
    val confidence: Confidence get() = point.confidence
}

/**
 * One leg of a [TripAssessment] - the weather-evaluated counterpart to [TripLeg]. Never
 * averages [from]/[to]/[weatherSamples] together; [worstCategory] is always the worst of the
 * three, matching the trip-level rule in [TripAssessmentBuilder] - see docs/TRIP_ASSESSMENT.md
 * "One hazardous segment must gate the whole trip."
 */
data class TripLegAssessment(
    val leg: TripLeg,
    val from: TripPointAssessment,
    val to: TripPointAssessment,
    /** Generated [WeatherSampleGenerator] points strictly between [from] and [to], in transit
     * order. Empty for a short leg - see [WeatherSampleGenerator]. */
    val weatherSamples: List<TripPointAssessment> = emptyList(),
) {
    val allPoints: List<TripPointAssessment> get() = listOf(from) + weatherSamples + listOf(to)

    val worstCategory: BoatingCategory get() = allPoints.map { it.category }.reduce(::worstCategory)

    val worstHazards: List<Hazard> get() = TripHazardRanking.rank(allPoints.flatMap { it.hazards })

    val confidence: Confidence get() = allPoints.map { it.confidence }.reduce { a, b -> a.worstOf(b) }
}

/**
 * The full result for one [MarineTripPlan] - see docs/TRIP_ASSESSMENT.md "Output shape."
 * [overallCategory] is always the worst category across every point on [timeline] (departure,
 * every generated weather sample, every user waypoint, and the destination) - never a blended
 * average, so one genuinely hazardous segment always determines the headline result even when
 * every other segment is calm. See docs/ROADMAP.md Sprint 5 "Trip overall scoring."
 */
data class TripAssessment(
    val plan: MarineTripPlan,
    /** Every assessed point in strict chronological/transit order: departure, then each leg's
     * weather samples and its arrival point, ending at the destination. This is the sequence a
     * trip-result timeline UI renders directly - see docs/TRIP_PLANNING.md's UI sketch. */
    val timeline: List<TripPointAssessment>,
    val legs: List<TripLegAssessment>,
    val overallCategory: BoatingCategory,
    val worstHazards: List<Hazard>,
    val confidence: Confidence,
    /** A single deterministic sentence naming the worst hazard and where it applies - e.g.
     * "Thunderstorm probability 62% near Sebastian Inlet." Null only when every point is
     * hazard-free. Never LLM-generated prose - built directly from [worstHazards]'s own
     * message plus the point it occurred at, exactly like [com.wakewindow.app.domain.scoring.BestWindowFinder]'s
     * own reason strings. */
    val mainConcern: String?,
    /** Set when at least one point's estimated arrival falls beyond the forecast horizon any
     * configured provider can meaningfully cover - see [TripPlanLimits.MAX_FORECAST_HORIZON] and
     * docs/TRIP_ASSESSMENT.md "Forecast horizon." Never blocks building/saving the plan; it only
     * means that far-future point's own category reads UNAVAILABLE rather than a fabricated
     * forecast. */
    val horizonWarning: String? = null,
    /** Non-empty only when the plan exceeded a documented [TripPlanLimits] ceiling - the
     * assessment still completes (never a hard failure), but callers should surface these. */
    val limitViolations: List<TripPlanLimitViolation> = emptyList(),
) {
    val departure: TripPointAssessment get() = timeline.first()
    val destination: TripPointAssessment get() = timeline.last()
    val waypoints: List<TripPointAssessment> get() = timeline.filter { it.kind == TripPointKind.WAYPOINT }
    val weatherSamples: List<TripPointAssessment> get() = timeline.filter { it.kind == TripPointKind.WEATHER_SAMPLE }
}

/**
 * Hazard de-duplication/ranking shared by [TripLegAssessment.worstHazards] and
 * [TripAssessmentBuilder] - deliberately simpler than
 * [com.wakewindow.app.domain.scoring.MarineScoreEngine]'s own `rankHazards`: that one weights
 * severity by proximity to a single "return" instant, a concept a one-way, multi-leg trip
 * doesn't have (see docs/TRIP_ASSESSMENT.md "Why trip scoring doesn't reuse MarineScoreEngine
 * directly"). Here severity alone ranks, ties broken by earliest occurrence, so the very first
 * point where trouble starts is what surfaces - not a bias toward the trip's end.
 */
object TripHazardRanking {
    private val MESSAGE_STABLE_TYPES = setOf(
        HazardType.MARINE_ALERT_ADVISORY,
        HazardType.MARINE_ALERT_SEVERE,
        HazardType.MARINE_ALERT_EXTREME,
    )

    private fun severityScore(hazard: Hazard): Double = when (hazard.categoryCap) {
        BoatingCategory.NO_GO -> 100.0
        BoatingCategory.POOR -> 60.0
        BoatingCategory.CAUTION -> 30.0
        else -> 10.0
    }

    fun rank(hazards: List<Hazard>): List<Hazard> =
        hazards
            .distinctBy { hazard ->
                if (hazard.type in MESSAGE_STABLE_TYPES) "${hazard.type}-${hazard.message}" else "${hazard.type}-${hazard.at}-${hazard.message}"
            }
            .sortedWith(compareByDescending<Hazard> { severityScore(it) }.thenBy { it.at })
}
