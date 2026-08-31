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
 * Whether a launch is actually usable right now, as a real fact distinct from whether it
 * exists at all - a ramp that exists but is [CLOSED] is a materially different thing to tell a
 * boater than one WakeWindow simply has no data for ([UNKNOWN]). Only ever set from an
 * explicit status field a source actually publishes (e.g. FWC's `Status`) - never inferred from
 * silence, and never trusted indefinitely: [MarineFacilityInfo.source]'s `retrievedAt` is how
 * stale a status record is, since the source dataset publishes no separate per-record
 * timestamp for [FacilityOperationalStatus] itself.
 */
enum class FacilityOperationalStatus {
    OPEN,
    CLOSED,
    PARTIALLY_OPEN,
    SEASONAL,
    UNKNOWN,
}

/**
 * Facility/launch intelligence for a [MarinePlace] - kept as its own type, separate from bare
 * place discovery, because a geocoder knowing a marina exists does not make it authoritative
 * for any of this. See docs/PRODUCT.md "Marine place / launch intelligence."
 *
 * Every field defaults to its "not yet known" state so the UI has real states to render
 * honestly for any place a facility provider hasn't covered - deliberately not an uncontrolled
 * web scraper (see docs/ROADMAP.md). As of Sprint 4, `FwcFacilityInfoProvider` populates this
 * for Florida FWC boat ramps specifically (see docs/DATA_SOURCES.md); every other source still
 * returns the all-unknown default. [source] is null until a real verified-data pipeline sets
 * it; once one does, each field arguably deserves its own [SourceReference] rather than one
 * shared per record - a single shared reference is an acceptable simplification because every
 * field currently populated comes from the same single fetch.
 */
data class MarineFacilityInfo(
    val phone: String? = null,
    val website: String? = null,
    val hours: String? = null,
    val launchFee: String? = null,
    val parkingFee: String? = null,
    val trailerParking: FacilityAvailability = FacilityAvailability.UNKNOWN,
    val rampLanes: Int? = null,
    /** The source's own free-text ramp classification (e.g. FWC's `RampType` - "Paved",
     * "Unimproved"), surfaced as-is rather than mapped into a WakeWindow-invented enum, since
     * the real value vocabulary isn't itself verified. */
    val rampType: String? = null,
    /** The source's own free-text access classification (e.g. FWC's `AccessType`), same
     * as-is rationale as [rampType]. */
    val accessType: String? = null,
    /** The water body this launch actually accesses (e.g. FWC's `WaterBodyName` -
     * "Banana River") - a real fact about the launch, not merely its street address. */
    val waterBodyName: String? = null,
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
    /** Free-text amenity listing exactly as the source published it (e.g. FWC's `Amenities`
     * field), when the source's delimiter/vocabulary isn't itself verified enough to parse
     * into individual [FacilityAvailability] fields without risking a wrong guess - honest raw
     * text beats an invented structured breakdown. */
    val amenitiesRaw: String? = null,
    val operationalStatus: FacilityOperationalStatus = FacilityOperationalStatus.UNKNOWN,
    /** The source's own status text verbatim (e.g. "Open for Business"), shown alongside the
     * classified [operationalStatus] so nothing is lost in translation. */
    val operationalStatusRaw: String? = null,
    val notes: String? = null,
    val source: SourceReference? = null,
) {
    val hasAnyVerifiedData: Boolean get() = source != null
}
