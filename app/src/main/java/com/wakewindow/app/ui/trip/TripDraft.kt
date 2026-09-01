package com.wakewindow.app.ui.trip

import com.wakewindow.app.domain.trip.MarineTripPlan
import com.wakewindow.app.domain.trip.PlanningWaypoint
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Instant
import java.time.ZoneId

/**
 * The in-progress trip being edited on [com.wakewindow.app.ui.tripplan.TripPlanScreen] - kept
 * separate from [MarineTripPlan] itself because a plan requires a non-null departure/
 * destination/departure-time by construction, but the screen must render sensibly while any (or
 * all) of those are still unset. [toPlanOrNull] is the only place a [MarineTripPlan] is ever
 * built from this, and only once every required field is actually present.
 */
data class TripDraft(
    val name: String = "",
    val departure: PlanningWaypoint? = null,
    val destination: PlanningWaypoint? = null,
    val waypoints: List<PlanningWaypoint> = emptyList(),
    val departureTime: Instant? = null,
    val vessel: VesselProfile? = null,
    val cruiseSpeedKts: Double? = null,
    val notes: String? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    /** Set once this draft has been saved/remembered at least once - see
     * [com.wakewindow.app.domain.trip.SavedTrip]. Reusing the same id turns a later save into a
     * real update instead of a duplicate. */
    val savedTripId: String? = null,
) {
    fun toPlanOrNull(defaultVessel: VesselProfile): MarineTripPlan? {
        val dep = departure ?: return null
        val dest = destination ?: return null
        val time = departureTime ?: return null
        return MarineTripPlan(
            departure = dep,
            destination = dest,
            departureTime = time,
            vessel = vessel ?: defaultVessel,
            zoneId = zoneId,
            waypoints = waypoints,
            cruiseSpeedKts = cruiseSpeedKts,
            notes = notes,
        )
    }
}

/** Which slot a place-search result should resolve into - lets [com.wakewindow.app.ui.launchsearch.LaunchSearchScreen]
 * be reused unchanged for trip waypoint picking (see docs/TRIP_PLANNING.md), routed purely by
 * this transient selection rather than a second search screen. */
sealed interface TripWaypointTarget {
    data object Departure : TripWaypointTarget
    data object Destination : TripWaypointTarget
    data object NewWaypoint : TripWaypointTarget
    data class WaypointAt(val index: Int) : TripWaypointTarget
}
