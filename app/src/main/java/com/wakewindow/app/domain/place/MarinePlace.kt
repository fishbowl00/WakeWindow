package com.wakewindow.app.domain.place

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference

enum class MarinePlaceType {
    BOAT_RAMP,
    MARINA,
    HARBOR,
    PORT,
    DOCK,
    YACHT_CLUB,
    ANCHORAGE,
    OTHER,
}

/**
 * A search/geocoding result - what a place-search provider (Photon) can tell us exists. This
 * is deliberately a *different, smaller* type than [MarinePlace]: a geocoder knowing a marina
 * exists at a coordinate does not make it authoritative for ramp fees, VHF channels, or gate
 * hours. See docs/PRODUCT.md "Marine place / launch intelligence."
 */
data class MarinePlaceCandidate(
    val name: String,
    val location: GeoPoint,
    val address: String?,
    val guessedType: MarinePlaceType,
)

/**
 * The fuller facility record a saved launch is built from. [discovery] came from place
 * search; every field under "facility intelligence" is separately sourced (or, this sprint,
 * simply absent) and must never be guessed - a missing field renders as "Not available" in
 * the UI, never a fabricated default. [facilitySource] is null until a real verified-data
 * source is wired up (see docs/ROADMAP.md); every non-null facility field should eventually
 * carry its own [SourceReference] rather than one blanket source for the whole record, but a
 * single shared reference is an acceptable simplification while every field is null anyway.
 */
data class MarinePlace(
    val id: String,
    val discovery: MarinePlaceCandidate,

    // Facility intelligence - all null ("Not available") until a verified source exists.
    val phone: String? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val launchFee: String? = null,
    val parkingFee: String? = null,
    val trailerParkingAvailable: Boolean? = null,
    val rampLanes: Int? = null,
    val floatingDockAvailable: Boolean? = null,
    val fuelAvailable: Boolean? = null,
    val pumpOutAvailable: Boolean? = null,
    val restroomAvailable: Boolean? = null,
    val freshwaterAvailable: Boolean? = null,
    val transientSlipsAvailable: Boolean? = null,
    val transientSlipCost: String? = null,
    val mooringAvailable: Boolean? = null,
    val mooringCost: String? = null,
    val reservationRequired: Boolean? = null,
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

    val facilitySource: SourceReference? = null,
) {
    val name: String get() = discovery.name
    val location: GeoPoint get() = discovery.location
    val type: MarinePlaceType get() = discovery.guessedType

    /** True once at least one facility-intelligence field beyond bare discovery is known. */
    val hasVerifiedFacilityData: Boolean get() = facilitySource != null
}
