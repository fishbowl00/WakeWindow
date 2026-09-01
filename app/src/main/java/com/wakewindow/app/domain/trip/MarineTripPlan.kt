package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * A manually-defined point along a [MarineTripPlan] - explicitly a *planning* waypoint, never a
 * navigation waypoint. WakeWindow does not verify a safe, navigable course exists between
 * consecutive waypoints, does not know the local waterway, and does not account for land,
 * shoals, or channels - see docs/TRIP_PLANNING.md and docs/ROADMAP.md's explicit non-goals
 * ("will not fabricate a marine route by reusing road-routing output - a straight line between
 * two ports routinely crosses land"). A [PlanningWaypoint] is the user's own claim about where
 * they intend to be, nothing more.
 */
data class PlanningWaypoint(
    val name: String,
    val location: GeoPoint,
    /** Set only when the user typed a specific expected arrival instead of letting
     * [MarineTripPlan.cruiseSpeedKts] estimate it - see [TripLegEstimator]. */
    val manualArrival: Instant? = null,
    /** Stable identity for this waypoint, independent of [name]/[location] - needed once a
     * [MarineTripPlan] is persisted (see [SavedTrip]) and for UI list operations (reorder,
     * remove) that must survive a name edit. Defaults to a fresh random ID so existing
     * call sites (tests, in-memory construction) need not supply one. */
    val id: String = UUID.randomUUID().toString(),
)

/**
 * Mode B: a port-to-port / multi-waypoint trip - see docs/TRIP_PLANNING.md. Distinct from
 * [com.wakewindow.app.domain.route.BoatingPlan] (Mode A: a single day, returning to the same
 * launch). Every distance derived from this plan (see [TripLeg.planningDistanceNm]) is a
 * geodesic *planning distance* between user-supplied points - never presented as a certified
 * navigable route length, and never used to imply WakeWindow has charted a safe course.
 */
data class MarineTripPlan(
    val departure: PlanningWaypoint,
    val destination: PlanningWaypoint,
    val departureTime: Instant,
    val vessel: VesselProfile,
    val zoneId: ZoneId,
    val waypoints: List<PlanningWaypoint> = emptyList(),
    /** Null if the user hasn't supplied one - see [TripLegEstimator] for how ETA estimation
     * degrades gracefully without it. */
    val cruiseSpeedKts: Double? = null,
    val notes: String? = null,
) {
    /** Every point in transit order: departure, then manual waypoints in the order given, then
     * destination. */
    val orderedPoints: List<PlanningWaypoint> get() = listOf(departure) + waypoints + listOf(destination)
}

/** One leg of a [MarineTripPlan] between two consecutive [PlanningWaypoint]s - see
 * [TripLegEstimator.estimateLegs]. */
data class TripLeg(
    val from: PlanningWaypoint,
    val to: PlanningWaypoint,
    /** Great-circle distance between the two user-supplied points - a *planning* distance, not
     * a certified navigable route length. See [MarineTripPlan]'s class doc. */
    val planningDistanceNm: Double,
    val estimatedArrival: Instant,
    /** True when [estimatedArrival] came from [PlanningWaypoint.manualArrival] rather than
     * being computed from [MarineTripPlan.cruiseSpeedKts]. */
    val isManualArrival: Boolean,
    /** False only when this leg has neither a manual arrival nor a usable cruise speed, so
     * [estimatedArrival] simply repeats the previous point's time as an honest placeholder
     * rather than a fabricated ETA. A caller should treat a plan containing any unresolved leg
     * as needing either a speed or a manual arrival filled in before its timing is genuinely
     * useful. */
    val isResolved: Boolean,
)

/**
 * Turns a [MarineTripPlan]'s user-supplied waypoints into timed legs and weather-evaluation
 * sample points - deliberately one sample per user-supplied point, never synthetic
 * intermediate samples along a route WakeWindow doesn't actually know the shape of (see the
 * sprint brief: "meaningful sample points... not continuous 500-point sampling").
 */
object TripLegEstimator {

    /**
     * Estimates arrival at every point after departure, in order. A waypoint's own
     * [PlanningWaypoint.manualArrival] always wins when set; otherwise arrival is computed from
     * the previous point's own estimated/actual time plus [MarineTripPlan.cruiseSpeedKts]. With
     * neither a cruise speed nor a manual arrival for a given leg, that leg's arrival simply
     * repeats the previous point's time - an honest "unresolved" placeholder (see
     * [TripLeg.isResolved]), never a fabricated ETA.
     */
    fun estimateLegs(plan: MarineTripPlan): List<TripLeg> {
        val points = plan.orderedPoints
        val legs = mutableListOf<TripLeg>()
        var previousTime = plan.departureTime
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            val distanceNm = from.location.distanceNmTo(to.location)
            val manualArrival = to.manualArrival
            val speed = plan.cruiseSpeedKts
            val (arrival, isManual, isResolved) = when {
                manualArrival != null -> Triple(manualArrival, true, true)
                speed != null && speed > 0.0 -> {
                    val hours = distanceNm / speed
                    Triple(previousTime.plusSeconds((hours * 3600.0).toLong()), false, true)
                }
                else -> Triple(previousTime, false, false)
            }
            legs += TripLeg(from, to, distanceNm, arrival, isManual, isResolved)
            previousTime = arrival
        }
        return legs
    }

    /** Total planning distance across every leg - see [TripLeg.planningDistanceNm]. */
    fun totalPlanningDistanceNm(plan: MarineTripPlan): Double = estimateLegs(plan).sumOf { it.planningDistanceNm }

    /** The plan's own estimated arrival at its final point (the destination) - null only when
     * the plan has no legs at all, which cannot happen for a valid [MarineTripPlan] (departure
     * and destination are always present). */
    fun estimatedArrival(plan: MarineTripPlan): Instant? = estimateLegs(plan).lastOrNull()?.estimatedArrival

    /** Wall-clock span from [MarineTripPlan.departureTime] to [estimatedArrival] - zero when the
     * trip is entirely unresolved (see [TripLeg.isResolved]), since an unresolved leg's arrival
     * simply repeats the previous point's time rather than fabricating a duration. */
    fun estimatedDuration(plan: MarineTripPlan): Duration {
        val arrival = estimatedArrival(plan) ?: return Duration.ZERO
        return Duration.between(plan.departureTime, arrival)
    }

    /**
     * One [RouteSample] per planning point (departure, each waypoint, destination) at that
     * point's own location and estimated arrival time - ready to hand to a weather-evaluation
     * path the same way [com.wakewindow.app.domain.route.BoatingPlan.defaultRouteSamples]
     * already is for Mode A. See [com.wakewindow.app.data.repository.DefaultTripBoatingRepository]
     * for the per-location, per-arrival-time fetch this feeds - docs/TRIP_PLANNING.md.
     */
    fun routeSamples(plan: MarineTripPlan): List<RouteSample> {
        val legs = estimateLegs(plan)
        val samples = mutableListOf(RouteSample(plan.departure.location, RouteSampleRole.DEPARTURE, 0.0, plan.departureTime))
        val totalDistance = legs.sumOf { it.planningDistanceNm }.takeIf { it > 0.0 } ?: 1.0
        var cumulativeDistance = 0.0
        legs.forEachIndexed { index, leg ->
            cumulativeDistance += leg.planningDistanceNm
            val role = if (index == legs.lastIndex) RouteSampleRole.DESTINATION else RouteSampleRole.WAYPOINT
            val progress = (cumulativeDistance / totalDistance).coerceIn(0.0, 1.0)
            samples += RouteSample(leg.to.location, role, progress, leg.estimatedArrival)
        }
        return samples
    }
}

/** A specific reason a [MarineTripPlan] exceeds a documented complexity limit - see
 * [TripPlanLimits]. Always a concrete, user-facing sentence, never a bare code. */
data class TripPlanLimitViolation(val message: String)

/**
 * Deliberate, documented ceilings on trip complexity - see docs/TRIP_PLANNING.md "Trip
 * complexity limits" for the rationale behind each number. These protect the app (and the
 * providers WakeWindow calls on the user's behalf) from pathological input - an accidental
 * 500-waypoint trip, or a plan spanning years - without arbitrarily blocking a real multi-day
 * cruise. [validate] never mutates or rejects a [MarineTripPlan] itself; it only reports
 * violations for a caller (the UI, [com.wakewindow.app.data.repository.DefaultTripBoatingRepository])
 * to act on.
 */
object TripPlanLimits {
    /** Departure + destination are unlimited (always exactly one each); this bounds only the
     * manually-added intermediate stops. Ten manual waypoints plus the two endpoints is already
     * far beyond any real single-day or multi-day recreational trip WakeWindow is designed for. */
    const val MAX_WAYPOINTS = 10

    /** Generated [WeatherSampleGenerator] samples per leg - see its own doc for the distance
     * thresholds that decide how many of this ceiling are actually used for a given leg. */
    const val MAX_WEATHER_SAMPLES_PER_LEG = 3

    /** Beyond this, no marine forecast provider WakeWindow uses has any meaningful skill left -
     * see docs/DATA_SOURCES.md forecast-horizon notes - so a plan this far out can still be
     * created and saved, but its assessment is reported as [com.wakewindow.app.domain.trip.TripAssessment]
     * with an honest unavailable/partial result rather than fabricated confidence. */
    val MAX_FORECAST_HORIZON: Duration = Duration.ofDays(7)

    /** A trip plan longer than this (departure to estimated final arrival) is rejected as
     * pathological input rather than assessed - not a real recreational boating trip. */
    val MAX_TRIP_DURATION: Duration = Duration.ofDays(14)

    fun validate(plan: MarineTripPlan): List<TripPlanLimitViolation> = buildList {
        if (plan.waypoints.size > MAX_WAYPOINTS) {
            add(TripPlanLimitViolation("A trip supports at most $MAX_WAYPOINTS planning waypoints (this plan has ${plan.waypoints.size})"))
        }
        val duration = TripLegEstimator.estimatedDuration(plan)
        if (duration > MAX_TRIP_DURATION) {
            add(TripPlanLimitViolation("This plan's estimated duration (${duration.toDays()} days) exceeds the ${MAX_TRIP_DURATION.toDays()}-day trip limit"))
        }
    }
}
