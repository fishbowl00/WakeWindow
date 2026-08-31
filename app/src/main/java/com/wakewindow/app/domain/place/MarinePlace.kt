package com.wakewindow.app.domain.place

import com.wakewindow.app.domain.model.GeoPoint

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
 * Where a [MarinePlaceCandidate] came from - shown honestly in search results rather than
 * presenting every result as equally authoritative. See docs/PLACE_DISCOVERY.md. A government
 * boating-facility inventory (FWC, USACE) is a materially stronger claim than a generic
 * geocoder tagging a point "boat_ramp" from crowd-sourced map data.
 */
enum class PlaceSourceType {
    /** Florida FWC's own statewide public boat ramp inventory - see docs/DATA_SOURCES.md. */
    FWC_BOAT_RAMP,
    /** USACE's recreation-area land parcels around Corps reservoirs - a real government
     * facility exists here, but (unlike [FWC_BOAT_RAMP]) this dataset does not itself assert a
     * boat ramp is present - see docs/DATA_SOURCES.md. */
    USACE_RECREATION_AREA,
    /** General-purpose keyless geocoding (Photon/OpenStreetMap) - the fallback, not the
     * authority, for boating-specific facilities. */
    GEOCODING,
}

/**
 * A search/geocoding result - what a place-search provider (Photon, FWC, USACE) can tell us
 * exists. This is deliberately a *different, smaller* type than [MarinePlace]: a geocoder
 * knowing a marina exists at a coordinate does not make it authoritative for ramp fees, VHF
 * channels, or gate hours. See docs/PRODUCT.md "Marine place / launch intelligence."
 */
data class MarinePlaceCandidate(
    val name: String,
    val location: GeoPoint,
    val address: String?,
    val guessedType: MarinePlaceType,
    val sourceType: PlaceSourceType = PlaceSourceType.GEOCODING,
)

/**
 * The fuller facility record a saved launch is built from. [discovery] came from place search
 * (name/coordinates/address/a guessed type); [facility] is entirely separate, provenance-
 * tracked launch intelligence - see [MarineFacilityInfo].
 */
data class MarinePlace(
    val id: String,
    val discovery: MarinePlaceCandidate,
    val facility: MarineFacilityInfo = MarineFacilityInfo(),
) {
    val name: String get() = discovery.name
    val location: GeoPoint get() = discovery.location
    val type: MarinePlaceType get() = discovery.guessedType
}
