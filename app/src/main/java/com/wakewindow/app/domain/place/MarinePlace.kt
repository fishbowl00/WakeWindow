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
