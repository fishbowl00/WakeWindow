package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Instant
import java.time.ZoneId

/**
 * A trip plan the user has saved for quick reuse ("Canaveral to Sebastian," "ICW south run") -
 * see docs/TRIP_PLANNING.md "Saved trips." Distinct from [MarineTripPlan] the same way
 * [com.wakewindow.app.domain.place.SavedLaunch] is distinct from a live plan: this stores the
 * user's reusable choices (points, vessel, cruise speed), never a specific past departure
 * instant or a stored assessment result - re-running a saved trip always evaluates weather
 * fresh for whatever departure time the user picks next.
 */
data class SavedTrip(
    val id: String,
    val name: String,
    val departure: PlanningWaypoint,
    val destination: PlanningWaypoint,
    val waypoints: List<PlanningWaypoint> = emptyList(),
    /** References a [VesselProfile.id] rather than embedding a full profile, exactly like
     * [com.wakewindow.app.domain.place.SavedLaunch.lastVesselProfileId] - a profile's own
     * details may keep changing (edited by the user) after this trip is saved. Null if no
     * vessel was chosen yet. */
    val vesselProfileId: String? = null,
    val cruiseSpeedKts: Double? = null,
    val notes: String? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val isFavorite: Boolean = false,
    val savedAtEpochMillis: Long,
    /** The last departure hour-of-day actually run for this trip - the Mode B counterpart to
     * [com.wakewindow.app.domain.place.SavedLaunch.lastDepartureHourOfDay], used the same way
     * for a "usually 7 AM" recall on the saved-trips list. Null until first run. */
    val lastDepartureHourOfDay: Int? = null,
) {
    val orderedPoints: List<PlanningWaypoint> get() = listOf(departure) + waypoints + listOf(destination)
}

/** Builds a live [MarineTripPlan] from this saved trip for a specific departure instant and
 * resolved vessel - the counterpart to how [WakeWindowViewModel][com.wakewindow.app.ui.WakeWindowViewModel]
 * turns a [com.wakewindow.app.domain.place.SavedLaunch] into a live [com.wakewindow.app.domain.route.BoatingPlan]. */
fun SavedTrip.toPlan(vessel: VesselProfile, departureTime: Instant): MarineTripPlan = MarineTripPlan(
    departure = departure,
    destination = destination,
    departureTime = departureTime,
    vessel = vessel,
    zoneId = zoneId,
    waypoints = waypoints,
    cruiseSpeedKts = cruiseSpeedKts,
    notes = notes,
)

/** Local persistence for saved trips - mirrors [com.wakewindow.app.domain.place.SavedLaunchRepository]'s
 * small, purpose-built shape rather than a generic CRUD abstraction. */
interface SavedTripRepository {
    suspend fun getAll(): List<SavedTrip>
    suspend fun save(trip: SavedTrip)
    suspend fun delete(id: String)
    suspend fun setFavorite(id: String, isFavorite: Boolean)
}
