package com.wakewindow.app.ui

import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.SavedLaunch
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
import com.wakewindow.app.domain.sun.SolarCalculator
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Duration
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
    /** Independent of [activeLaunch] - viewing a launch's facility info must never disturb an
     * in-progress plan for a different (or the same) launch. */
    val infoLaunch: SavedLaunch? = null,
    val isLoadingFacilityInfo: Boolean = false,
    val facilityInfoError: String? = null,
    val departureTime: Instant? = null,
    val returnTime: Instant? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val vessel: VesselProfile = VesselProfile.default(),
    /** User-created/edited profiles only - see docs/VESSEL_PROFILES.md. The five built-in
     * presets are always available via [VesselProfile.presets] and never stored here. */
    val customVessels: List<VesselProfile> = emptyList(),

    val assessment: BoatingWindowAssessment? = null,
    val isLoadingAssessment: Boolean = false,
    val assessmentError: String? = null,
) {
    val hasAnySavedLaunch: Boolean get() = savedLaunches.isNotEmpty()

    /** Every vessel selectable in the UI - the five built-in presets first, then any
     * user-saved custom profiles. */
    val availableVessels: List<VesselProfile> get() = VesselProfile.presets() + customVessels

    /** Real sunrise/sunset/civil-twilight for the departure date at the active launch - see
     * [SolarCalculator]. Null before a launch and departure date are both chosen; a closed-form
     * calculation, not a network fetch, so it's safe to recompute on every read. */
    val sunTimes: SolarCalculator.SunTimes?
        get() {
            val location = activeLaunch?.place?.location ?: return null
            val date = departureTime?.atZone(zoneId)?.toLocalDate() ?: return null
            return SolarCalculator.calculate(location, date)
        }

    /** Minutes the planned return falls after sunset, or null if the return is at/before
     * sunset (or sunset itself couldn't be resolved) - purely informational, never a hazard on
     * its own; see docs/PLANNING.md "Daylight context." */
    val returnAfterSunsetMinutes: Long?
        get() {
            val sunset = sunTimes?.sunset ?: return null
            val ret = returnTime ?: return null
            return Duration.between(sunset, ret).toMinutes().takeIf { it > 0 }
        }
}
