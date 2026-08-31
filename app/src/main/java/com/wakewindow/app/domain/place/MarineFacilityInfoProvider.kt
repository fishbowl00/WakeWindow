package com.wakewindow.app.domain.place

sealed interface FacilityInfoOutcome {
    data class Success(val facility: MarineFacilityInfo) : FacilityInfoOutcome
    /** No verified facility data exists for this place yet - a real, permanent fact this
     * sprint (no provider is wired up), not a transient error. */
    data object NoDataAvailable : FacilityInfoOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : FacilityInfoOutcome
}

/**
 * Seam for a future controlled facility-intelligence source (an official port/harbor-master
 * API, a curated dataset WakeWindow maintains, etc.) - see docs/ROADMAP.md "Launch
 * intelligence" for why an uncontrolled web scraper against arbitrary marina websites is
 * explicitly out of scope rather than attempted here. No implementation ships this sprint;
 * [com.wakewindow.app.ui.launchinfo.LaunchInfoScreen] is built to render
 * [MarineFacilityInfo]'s default (all-unknown) state honestly either way.
 */
interface MarineFacilityInfoProvider {
    suspend fun facilityInfoFor(place: MarinePlaceCandidate): FacilityInfoOutcome
}
