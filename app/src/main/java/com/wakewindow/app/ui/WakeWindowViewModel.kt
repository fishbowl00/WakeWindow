package com.wakewindow.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wakewindow.app.AppDependencies
import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.PlaceSearchOutcome
import com.wakewindow.app.domain.place.SavedLaunch
import com.wakewindow.app.domain.route.BoatingPlan
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
    private val placeProvider = AppDependencies.placeProvider()
    private val boatingRepository = AppDependencies.boatingRepository()
    private val vesselPreferenceStore = AppDependencies.vesselPreferenceStore(application)

    private val _uiState = MutableStateFlow(WakeWindowUiState(vessel = vesselPreferenceStore.loadSelectedPreset() ?: VesselProfile.default()))
    val uiState: StateFlow<WakeWindowUiState> = _uiState.asStateFlow()

    init {
        loadSavedLaunches()
    }

    /** Changing vessel never overrides an active marine warning gate or hides a hazard - it
     * only changes which tolerances [com.wakewindow.app.domain.scoring.MarinePointScorer]
     * scores against. Persisted immediately so the choice survives app restarts - see
     * [com.wakewindow.app.data.local.VesselPreferenceStore]. */
    fun setVessel(profile: VesselProfile) {
        vesselPreferenceStore.saveSelectedPreset(profile)
        _uiState.update { it.copy(vessel = profile) }
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
            when (val outcome = placeProvider.search(query)) {
                is PlaceSearchOutcome.Success -> _uiState.update {
                    it.copy(isSearching = false, searchResults = outcome.candidates)
                }
                is PlaceSearchOutcome.Failure -> _uiState.update {
                    it.copy(isSearching = false, searchError = outcome.message)
                }
            }
        }
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

    fun viewLaunchInfo(launch: SavedLaunch) {
        _uiState.update { it.copy(infoLaunch = launch) }
    }

    private suspend fun activateLaunch(launch: SavedLaunch) {
        val zone = AppDependencies.nwsProviders.resolveZoneId(launch.place.location)
        val now = Instant.now().atZone(zone)
        val defaultDurationMinutes = 8 * 60L
        val departure = now.plusMinutes(60).withMinute(0).withSecond(0).withNano(0).toInstant()
        val returnTime = departure.plusSeconds(defaultDurationMinutes * 60)
        _uiState.update {
            it.copy(
                activeLaunch = launch,
                zoneId = zone,
                departureTime = departure,
                returnTime = returnTime,
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

    fun setReturnTime(instant: Instant) {
        _uiState.update { it.copy(returnTime = instant) }
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
            }
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
