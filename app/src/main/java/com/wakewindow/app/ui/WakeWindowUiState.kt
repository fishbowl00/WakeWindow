package com.wakewindow.app.ui

import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.SavedLaunch
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Instant
import java.time.ZoneId

data class WakeWindowUiState(
    val savedLaunches: List<SavedLaunch> = emptyList(),
    val savedLaunchesLoaded: Boolean = false,

    val searchQuery: String = "",
    val searchResults: List<MarinePlaceCandidate> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,

    val activeLaunch: SavedLaunch? = null,
    val departureTime: Instant? = null,
    val returnTime: Instant? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val vessel: VesselProfile = VesselProfile.default(),

    val assessment: BoatingWindowAssessment? = null,
    val isLoadingAssessment: Boolean = false,
    val assessmentError: String? = null,
) {
    val hasAnySavedLaunch: Boolean get() = savedLaunches.isNotEmpty()
}
