package com.wakewindow.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wakewindow.app.AppDependencies
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.FacilityInfoOutcome
import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import com.wakewindow.app.domain.place.SavedLaunch
import com.wakewindow.app.domain.route.BoatingPlan
import com.wakewindow.app.domain.route.QuickPlanKind
import com.wakewindow.app.domain.route.QuickPlanPresets
import com.wakewindow.app.domain.trip.PlanningWaypoint
import com.wakewindow.app.domain.trip.SavedTrip
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.ui.trip.TripWaypointTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class WakeWindowViewModel(application: Application) : AndroidViewModel(application) {

    private val savedLaunchRepository = AppDependencies.savedLaunchRepository(application)
    private val placeProvider = AppDependencies.placeProvider(application)
    private val boatingRepository = AppDependencies.boatingRepository(application)
    private val tripBoatingRepository = AppDependencies.tripBoatingRepository(application)
    private val savedTripRepository = AppDependencies.savedTripRepository(application)
    private val vesselPreferenceStore = AppDependencies.vesselPreferenceStore(application)
    private val vesselProfileRepository = AppDependencies.vesselProfileRepository(application)
    private val facilityInfoProvider = AppDependencies.facilityInfoProvider(application)

    private val _uiState = MutableStateFlow(WakeWindowUiState(vessel = VesselProfile.default()))
    val uiState: StateFlow<WakeWindowUiState> = _uiState.asStateFlow()

    init {
        loadSavedLaunches()
        loadVesselProfiles()
        loadSavedTrips()
    }

    /** Changing vessel never overrides an active marine warning gate or hides a hazard - it
     * only changes which tolerances [com.wakewindow.app.domain.scoring.MarinePointScorer]
     * scores against. Persisted immediately so the choice survives app restarts - see
     * [com.wakewindow.app.data.local.VesselPreferenceStore]. */
    fun setVessel(profile: VesselProfile) {
        vesselPreferenceStore.saveSelectedProfileId(profile.id)
        _uiState.update { it.copy(vessel = profile) }
    }

    private fun loadVesselProfiles() {
        viewModelScope.launch {
            val custom = vesselProfileRepository.getAllCustom()
            val selected = vesselPreferenceStore.loadSelectedProfile(custom)
            _uiState.update { it.copy(customVessels = custom, vessel = selected) }
        }
    }

    /**
     * Saves a user-created or user-edited vessel profile (see docs/VESSEL_PROFILES.md
     * "Planning preferences, not safe limits") and makes it the active vessel. [profile] must
     * already carry a real, stable ID (see [VesselProfile.withNewId]) - editing an existing
     * saved profile reuses its ID so this genuinely updates it rather than creating a
     * duplicate.
     */
    /** [VesselProfile.markCustomized] (not a plain `copy(isCustom = true)`) so a profile that
     * somehow reaches here still carrying a preset's `id` (its own name) is never persisted
     * that way - see docs/VESSEL_PROFILES.md. The editor screen already calls it on every
     * field change, so this is a backstop, not the only place it happens. */
    fun saveVesselProfile(profile: VesselProfile) {
        viewModelScope.launch {
            val customProfile = profile.markCustomized()
            vesselProfileRepository.save(customProfile)
            val custom = vesselProfileRepository.getAllCustom()
            vesselPreferenceStore.saveSelectedProfileId(customProfile.id)
            _uiState.update { it.copy(customVessels = custom, vessel = custom.firstOrNull { it.id == customProfile.id } ?: customProfile) }
        }
    }

    fun deleteVesselProfile(id: String) {
        viewModelScope.launch {
            vesselProfileRepository.delete(id)
            val custom = vesselProfileRepository.getAllCustom()
            val stillSelected = _uiState.value.vessel.id != id
            _uiState.update {
                it.copy(
                    customVessels = custom,
                    vessel = if (stillSelected) it.vessel else VesselProfile.default(),
                )
            }
            if (!stillSelected) vesselPreferenceStore.saveSelectedProfileId(VesselProfile.default().id)
        }
    }

    fun loadSavedLaunches() {
        viewModelScope.launch {
            val launches = savedLaunchRepository.getAll()
            _uiState.update { it.copy(savedLaunches = launches, savedLaunchesLoaded = true) }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun search() {
        val query = _uiState.value.searchQuery
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchError = null) }
            // placeProvider.search() can throw - e.g. a corrupted DurableCache entry failing to
            // deserialize - and there's no CoroutineExceptionHandler backstopping viewModelScope,
            // so an uncaught exception here would crash the app rather than surface a search
            // error, matching the try/catch already around viewLaunchInfo()'s equivalent call.
            val outcome = try {
                placeProvider.search(query, searchBias())
            } catch (e: Exception) {
                PlaceSearchOutcome.Failure(e.message ?: "Search failed", e)
            }
            when (outcome) {
                is PlaceSearchOutcome.Success -> _uiState.update {
                    it.copy(isSearching = false, searchResults = outcome.candidates)
                }
                is PlaceSearchOutcome.Failure -> _uiState.update {
                    it.copy(isSearching = false, searchError = outcome.message)
                }
            }
        }
    }

    /**
     * Biases search results toward the boater's own area without ever requiring device
     * location permission - see docs/PLACE_DISCOVERY.md "Location bias." Device/GPS location
     * bias is deliberately not implemented this sprint (it would need a new location-services
     * dependency and a runtime-permission flow that can't be verified without a physical
     * device - see docs/ROADMAP.md); the currently-active plan's launch, or otherwise the most
     * recently saved launch, is a real proxy for "the boater's area" that needs neither. With
     * no saved launches at all, search remains unbiased text relevance only, exactly as before.
     */
    private fun searchBias(): GeoPoint? {
        val state = _uiState.value
        return state.activeLaunch?.place?.location ?: state.savedLaunches.firstOrNull()?.place?.location
    }

    /** Saves the chosen candidate as a launch, makes it the active plan's launch, and
     * resolves default departure/return times at the launch's own time zone - see
     * docs/ARCHITECTURE.md "Time zone handling." Reused for trip waypoint search too (see
     * [com.wakewindow.app.ui.launchsearch.LaunchSearchScreen]) - when [WakeWindowUiState.tripSearchTarget]
     * is set, the candidate resolves into a [PlanningWaypoint] on the in-progress [com.wakewindow.app.ui.trip.TripDraft]
     * instead, and is never persisted as a [SavedLaunch] (a trip waypoint is not a saved launch). */
    fun selectSearchCandidate(candidate: MarinePlaceCandidate) {
        val tripTarget = _uiState.value.tripSearchTarget
        if (tripTarget != null) {
            selectTripWaypointCandidate(candidate, tripTarget)
            return
        }
        viewModelScope.launch {
            val launch = SavedLaunch(
                id = UUID.randomUUID().toString(),
                place = MarinePlace(id = UUID.randomUUID().toString(), discovery = candidate),
                isFavorite = false,
                savedAtEpochMillis = System.currentTimeMillis(),
            )
            savedLaunchRepository.save(launch)
            loadSavedLaunches()
            activateLaunch(launch)
        }
    }

    fun selectSavedLaunch(launch: SavedLaunch) {
        viewModelScope.launch { activateLaunch(launch) }
    }

    /** Shows the launch immediately with whatever facility data it already carries, then fetches
     * real facility intelligence in the background - see [com.wakewindow.app.domain.place.MarineFacilityInfoProvider].
     * Every non-FWC place simply gets [FacilityInfoOutcome.NoDataAvailable] back and stays at
     * its honest all-unknown default; that is not an error and is never surfaced as one. */
    fun viewLaunchInfo(launch: SavedLaunch) {
        _uiState.update { it.copy(infoLaunch = launch, isLoadingFacilityInfo = false, facilityInfoError = null) }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFacilityInfo = true) }
            val outcome = try {
                facilityInfoProvider.facilityInfoFor(launch.place.discovery)
            } catch (e: Exception) {
                FacilityInfoOutcome.Failure(e.message ?: "Could not load facility information", e)
            }
            _uiState.update { state ->
                // The user may have navigated to a different launch's info (or away entirely)
                // while this fetch was in flight - never let a stale response overwrite it.
                if (state.infoLaunch?.id != launch.id) return@update state
                when (outcome) {
                    is FacilityInfoOutcome.Success -> state.copy(
                        isLoadingFacilityInfo = false,
                        infoLaunch = launch.copy(place = launch.place.copy(facility = outcome.facility)),
                    )
                    FacilityInfoOutcome.NoDataAvailable -> state.copy(isLoadingFacilityInfo = false)
                    is FacilityInfoOutcome.Failure -> state.copy(isLoadingFacilityInfo = false, facilityInfoError = outcome.message)
                }
            }
        }
    }

    /**
     * Resolves the plan to start from - "your usual" (see [SavedLaunch]'s "Recent plans") when
     * this launch has one, an hour from now for 8 hours otherwise, exactly as before. A launch
     * that remembers "usually 7am for about 6 hours in the Center Console" is faster to plan
     * the second time without the user re-entering anything.
     */
    private suspend fun activateLaunch(launch: SavedLaunch) {
        val zone = AppDependencies.nwsProviders.resolveZoneId(launch.place.location)
        val now = Instant.now().atZone(zone)

        val departure: Instant
        val returnTime: Instant
        if (launch.lastDepartureHourOfDay != null && launch.lastDurationMinutes != null) {
            val todayAtUsualHour = now.withHour(launch.lastDepartureHourOfDay).withMinute(0).withSecond(0).withNano(0)
            val candidate = if (todayAtUsualHour.isAfter(now)) todayAtUsualHour else todayAtUsualHour.plusDays(1)
            departure = candidate.toInstant()
            returnTime = departure.plusSeconds(launch.lastDurationMinutes * 60)
        } else {
            departure = now.plusMinutes(60).withMinute(0).withSecond(0).withNano(0).toInstant()
            returnTime = departure.plusSeconds(8 * 60 * 60L)
        }

        val vessel = launch.lastVesselProfileId
            ?.let { id -> _uiState.value.availableVessels.firstOrNull { it.id == id } }
            ?: _uiState.value.vessel

        _uiState.update {
            it.copy(
                activeLaunch = launch,
                zoneId = zone,
                departureTime = departure,
                returnTime = returnTime,
                vessel = vessel,
                assessment = null,
                assessmentError = null,
            )
        }
    }

    fun setDepartureTime(instant: Instant) {
        _uiState.update { state ->
            val newReturn = state.returnTime?.takeIf { it.isAfter(instant) } ?: instant.plusSeconds(6 * 3600)
            state.copy(departureTime = instant, returnTime = newReturn)
        }
    }

    /** Mirrors [setDepartureTime]'s own guard: a return time at or before the current departure
     * would build a [com.wakewindow.app.domain.route.BoatingPlan] with a zero/negative duration
     * that renders as a nonsensical "0h" outing rather than surfacing the inconsistency - see
     * docs/PLANNING.md. Falls back to departure + 1 hour, the same minimum duration
     * [com.wakewindow.app.domain.route.QuickPlanPresets] guarantees for every computed window. */
    fun setReturnTime(instant: Instant) {
        _uiState.update { state ->
            val departure = state.departureTime
            val corrected = if (departure != null && !instant.isAfter(departure)) departure.plusSeconds(3600) else instant
            state.copy(returnTime = corrected)
        }
    }

    /** Applies a quick-plan shortcut (Morning/Afternoon/Evening/Full day) - see
     * [com.wakewindow.app.domain.route.QuickPlanPresets]. A no-op for [QuickPlanKind.CUSTOM],
     * which just means "keep whatever the manual pickers already have." Uses real sunrise/
     * sunset for the departure date when a launch is active, falling back to deterministic
     * clock defaults otherwise. */
    fun applyQuickPlan(kind: QuickPlanKind) {
        if (kind == QuickPlanKind.CUSTOM) return
        val state = _uiState.value
        val date = (state.departureTime ?: Instant.now()).atZone(state.zoneId).toLocalDate()
        val window = QuickPlanPresets.windowFor(kind, date, state.zoneId, state.sunTimes)
        _uiState.update { it.copy(departureTime = window.departure, returnTime = window.returnTime) }
    }

    fun runAssessment() {
        val state = _uiState.value
        val launch = state.activeLaunch ?: return
        val departure = state.departureTime ?: return
        val returnTime = state.returnTime ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAssessment = true, assessmentError = null) }
            try {
                val plan = BoatingPlan(
                    launch = launch.place,
                    departureTime = departure,
                    returnTime = returnTime,
                    vessel = state.vessel,
                    zoneId = state.zoneId,
                )
                val assessment = boatingRepository.buildAssessment(plan)
                _uiState.update { it.copy(isLoadingAssessment = false, assessment = assessment) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingAssessment = false, assessmentError = e.message ?: "Could not build an assessment") }
                return@launch
            }
            try {
                rememberPlan(launch, departure, returnTime, state.vessel)
            } catch (e: Exception) {
                // See rememberPlan's doc comment - the assessment itself already succeeded by
                // this point, so a failure to persist "your usual plan" must never read as an
                // assessment error.
            }
        }
    }

    /** Persists this plan's departure hour/duration/vessel back onto its [SavedLaunch] - see
     * "Recent plans" on [SavedLaunch]. Fire-and-forget from the caller's perspective: a failure
     * here should never surface as an assessment error, since the assessment itself already
     * succeeded by the time this runs. */
    private suspend fun rememberPlan(launch: SavedLaunch, departure: Instant, returnTime: Instant, vessel: VesselProfile) {
        val zone = _uiState.value.zoneId
        val updated = launch.copy(
            lastDepartureHourOfDay = departure.atZone(zone).hour,
            lastDurationMinutes = java.time.Duration.between(departure, returnTime).toMinutes(),
            lastVesselProfileId = vessel.id,
        )
        savedLaunchRepository.save(updated)
        _uiState.update { state ->
            state.copy(
                activeLaunch = if (state.activeLaunch?.id == launch.id) updated else state.activeLaunch,
                savedLaunches = state.savedLaunches.map { if (it.id == updated.id) updated else it },
            )
        }
    }

    fun setFavorite(launchId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            savedLaunchRepository.setFavorite(launchId, isFavorite)
            loadSavedLaunches()
        }
    }

    fun deleteLaunch(launchId: String) {
        viewModelScope.launch {
            savedLaunchRepository.delete(launchId)
            loadSavedLaunches()
        }
    }

    // ============================================================================
    // Mode B: trip planning (Sprint 5) - see docs/TRIP_PLANNING.md and docs/TRIP_ASSESSMENT.md.
    // ============================================================================

    fun loadSavedTrips() {
        viewModelScope.launch {
            val trips = savedTripRepository.getAll()
            _uiState.update { it.copy(savedTrips = trips, savedTripsLoaded = true) }
        }
    }

    /** Resets the trip draft to a blank in-progress trip, defaulting departure time to an hour
     * from now (rounded to the top of the hour) - the same "an hour out, round hour" default
     * [activateLaunch] uses for a fresh Mode A plan with no prior history. */
    fun startNewTrip() {
        val now = Instant.now().atZone(_uiState.value.zoneId)
        val defaultDeparture = now.plusMinutes(60).withMinute(0).withSecond(0).withNano(0).toInstant()
        _uiState.update {
            it.copy(
                tripDraft = com.wakewindow.app.ui.trip.TripDraft(departureTime = defaultDeparture, vessel = it.vessel),
                tripAssessment = null,
                tripAssessmentError = null,
            )
        }
    }

    fun selectSavedTrip(trip: SavedTrip) {
        val state = _uiState.value
        val vessel = trip.vesselProfileId?.let { id -> state.availableVessels.firstOrNull { it.id == id } } ?: state.vessel
        val now = Instant.now().atZone(trip.zoneId)
        val departureTime = if (trip.lastDepartureHourOfDay != null) {
            val todayAtUsualHour = now.withHour(trip.lastDepartureHourOfDay).withMinute(0).withSecond(0).withNano(0)
            (if (todayAtUsualHour.isAfter(now)) todayAtUsualHour else todayAtUsualHour.plusDays(1)).toInstant()
        } else {
            now.plusMinutes(60).withMinute(0).withSecond(0).withNano(0).toInstant()
        }
        val draft = com.wakewindow.app.ui.trip.TripDraft(
            name = trip.name,
            departure = trip.departure,
            destination = trip.destination,
            waypoints = trip.waypoints,
            departureTime = departureTime,
            vessel = vessel,
            cruiseSpeedKts = trip.cruiseSpeedKts,
            notes = trip.notes,
            zoneId = trip.zoneId,
            savedTripId = trip.id,
        )
        _uiState.update { it.copy(tripDraft = draft, tripAssessment = null, tripAssessmentError = null) }
    }

    fun setTripName(name: String) {
        _uiState.update { it.copy(tripDraft = it.tripDraft.copy(name = name)) }
    }

    fun setTripDepartureTime(instant: Instant) {
        _uiState.update { it.copy(tripDraft = it.tripDraft.copy(departureTime = instant)) }
    }

    fun setTripVessel(vessel: VesselProfile) {
        _uiState.update { it.copy(tripDraft = it.tripDraft.copy(vessel = vessel)) }
    }

    fun setTripCruiseSpeed(kts: Double?) {
        _uiState.update { it.copy(tripDraft = it.tripDraft.copy(cruiseSpeedKts = kts?.takeIf { it > 0.0 })) }
    }

    fun setTripNotes(notes: String) {
        _uiState.update { it.copy(tripDraft = it.tripDraft.copy(notes = notes.ifBlank { null })) }
    }

    fun removeTripWaypoint(id: String) {
        _uiState.update { it.copy(tripDraft = it.tripDraft.copy(waypoints = it.tripDraft.waypoints.filterNot { wp -> wp.id == id })) }
    }

    /** Opens the (reused) [com.wakewindow.app.ui.launchsearch.LaunchSearchScreen] for a
     * specific trip slot - see [TripWaypointTarget]. */
    fun startTripWaypointSearch(target: TripWaypointTarget) {
        _uiState.update { it.copy(tripSearchTarget = target, searchQuery = "", searchResults = emptyList(), searchError = null) }
    }

    private fun selectTripWaypointCandidate(candidate: MarinePlaceCandidate, target: TripWaypointTarget) {
        viewModelScope.launch {
            val waypoint = PlanningWaypoint(candidate.name, candidate.location)
            // Only the departure point's time zone matters for the plan overall - see
            // docs/ARCHITECTURE.md "Time zone handling" and docs/TRIP_PLANNING.md's own
            // documented limitation: intermediate/destination points may cross time zones, and
            // WakeWindow does not yet resolve a per-point zone for display.
            val zone = if (target is TripWaypointTarget.Departure) {
                AppDependencies.nwsProviders.resolveZoneId(candidate.location)
            } else {
                null
            }
            _uiState.update { state ->
                val draft = state.tripDraft
                val updated = when (target) {
                    is TripWaypointTarget.Departure -> draft.copy(departure = waypoint, zoneId = zone ?: draft.zoneId)
                    is TripWaypointTarget.Destination -> draft.copy(destination = waypoint)
                    is TripWaypointTarget.NewWaypoint -> draft.copy(waypoints = draft.waypoints + waypoint)
                    is TripWaypointTarget.WaypointAt -> draft.copy(
                        waypoints = draft.waypoints.mapIndexed { i, wp -> if (i == target.index) waypoint else wp },
                    )
                }
                state.copy(tripDraft = updated, tripSearchTarget = null)
            }
        }
    }

    /** Mirrors [runAssessment]'s exact defensive shape (try/catch around the fan-out call,
     * since `viewModelScope` has no `CoroutineExceptionHandler`) for Mode B. */
    fun runTripAssessment() {
        val state = _uiState.value
        val plan = state.tripDraft.toPlanOrNull(state.vessel) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTripAssessment = true, tripAssessmentError = null) }
            try {
                val assessment = tripBoatingRepository.buildTripAssessment(plan)
                _uiState.update { it.copy(isLoadingTripAssessment = false, tripAssessment = assessment) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingTripAssessment = false, tripAssessmentError = e.message ?: "Could not build a trip assessment") }
                return@launch
            }
            try {
                rememberTripDraft(plan)
            } catch (e: Exception) {
                // See rememberPlan's doc comment for the identical rationale - the assessment
                // already succeeded by this point, so a failure to persist the trip must never
                // read as an assessment error.
            }
        }
    }

    /** Persists the trip draft as a [SavedTrip] after a successful assessment - the Mode B
     * counterpart to [rememberPlan]. Reuses [com.wakewindow.app.ui.trip.TripDraft.savedTripId]
     * when present so re-running an already-saved trip updates it in place rather than creating
     * a duplicate entry. */
    private suspend fun rememberTripDraft(plan: com.wakewindow.app.domain.trip.MarineTripPlan) {
        val state = _uiState.value
        val draft = state.tripDraft
        val id = draft.savedTripId ?: java.util.UUID.randomUUID().toString()
        val name = draft.name.ifBlank { "${plan.departure.name} → ${plan.destination.name}" }
        val existingFavorite = state.savedTrips.firstOrNull { it.id == id }?.isFavorite ?: false
        val trip = SavedTrip(
            id = id,
            name = name,
            departure = plan.departure,
            destination = plan.destination,
            waypoints = plan.waypoints,
            vesselProfileId = plan.vessel.id,
            cruiseSpeedKts = plan.cruiseSpeedKts,
            notes = plan.notes,
            zoneId = plan.zoneId,
            isFavorite = existingFavorite,
            savedAtEpochMillis = System.currentTimeMillis(),
            lastDepartureHourOfDay = plan.departureTime.atZone(plan.zoneId).hour,
        )
        savedTripRepository.save(trip)
        _uiState.update { s ->
            s.copy(
                tripDraft = s.tripDraft.copy(savedTripId = id),
                savedTrips = (s.savedTrips.filterNot { it.id == id } + trip).sortedByDescending { it.savedAtEpochMillis },
            )
        }
    }

    fun setTripFavorite(tripId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            savedTripRepository.setFavorite(tripId, isFavorite)
            loadSavedTrips()
        }
    }

    fun deleteTrip(tripId: String) {
        viewModelScope.launch {
            savedTripRepository.delete(tripId)
            loadSavedTrips()
        }
    }
}
