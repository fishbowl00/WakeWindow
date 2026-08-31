package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Instant
import java.time.ZoneId

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

    /**
     * One [RouteSample] per planning point (departure, each waypoint, destination) at that
     * point's own location and estimated arrival time - ready to hand to a weather-evaluation
     * path the same way [com.wakewindow.app.domain.route.BoatingPlan.defaultRouteSamples]
     * already is for Mode A. Wiring this into a real per-location forecast fetch (today's
     * [com.wakewindow.app.data.repository.DefaultBoatingRepository] only ever samples one
     * location) is a scoped follow-up, not attempted this sprint - see docs/TRIP_PLANNING.md.
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
