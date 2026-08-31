package com.wakewindow.app.domain.place

import com.wakewindow.app.domain.model.SourceReference

/**
 * For a fact that is fundamentally yes/no, "we don't know" and "no" are different claims and
 * must never be conflated - see docs/PRODUCT.md "Facility data states." Absence of
 * information is not proof of absence: a launch page that says nothing about pump-out service
 * is [UNKNOWN], not [NOT_AVAILABLE].
 */
enum class FacilityAvailability {
    AVAILABLE,
    NOT_AVAILABLE,
    UNKNOWN,
    /** The concept doesn't apply to this place at all (e.g. "transient slips" at a bare boat
     * ramp with no docking) - a third state distinct from both "yes" and "we don't know." */
    NOT_APPLICABLE,
}

/**
 * Facility/launch intelligence for a [MarinePlace] - kept as its own type, separate from bare
 * place discovery, because a geocoder knowing a marina exists does not make it authoritative
 * for any of this. See docs/PRODUCT.md "Marine place / launch intelligence."
 *
 * No provider populates this from live data this sprint (see docs/ROADMAP.md - deliberately
 * not building an uncontrolled web scraper); every field defaults to its "not yet known"
 * state so the UI has real states to render honestly rather than a placeholder to fake.
 * [source] is null until a real verified-data pipeline exists; once one does, each field
 * arguably deserves its own [SourceReference] rather than one shared per record - a single
 * shared reference is an acceptable simplification only because nothing sets any field yet.
 */
data class MarineFacilityInfo(
    val phone: String? = null,
    val website: String? = null,
    val hours: String? = null,
    val launchFee: String? = null,
    val parkingFee: String? = null,
    val trailerParking: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val rampLanes: Int? = null,
    val floatingDock: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val fuel: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val pumpOut: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val restroom: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val freshwater: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val transientSlips: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val transientSlipCost: String? = null,
    val mooring: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val mooringCost: String? = null,
    val reservationRequired: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val reservationUrl: String? = null,
    val launchRestrictions: String? = null,
    val vesselRestrictions: String? = null,
    val parkingRestrictions: String? = null,
    val gateHours: String? = null,
    val vhfCallingChannel: String? = null,
    val harborMasterChannel: String? = null,
    val harborMasterPhone: String? = null,
    val localNotices: String? = null,
    val notes: String? = null,
    val source: SourceReference? = null,
) {
    val hasAnyVerifiedData: Boolean get() = source != null
}
