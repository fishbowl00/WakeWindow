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
import com.wakewindow.app.domain.vessel.VesselProfile
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
    private val boatingRepository = AppDependencies.boatingRepository()
    private val vesselPreferenceStore = AppDependencies.vesselPreferenceStore(application)
    private val vesselProfileRepository = AppDependencies.vesselProfileRepository(application)
    private val facilityInfoProvider = AppDependencies.facilityInfoProvider(application)

    private val _uiState = MutableStateFlow(WakeWindowUiState(vessel = VesselProfile.default()))
    val uiState: StateFlow<WakeWindowUiState> = _uiState.asStateFlow()

    init {
        loadSavedLaunches()
        loadVesselProfiles()
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
     * docs/ARCHITECTURE.md "Time zone handling." */
    fun selectSearchCandidate(candidate: MarinePlaceCandidate) {
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
}
