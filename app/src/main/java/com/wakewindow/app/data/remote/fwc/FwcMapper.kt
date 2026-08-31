package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType

/**
 * Pure query-building and DTO -> domain mapping for the FWC boat ramp inventory, kept separate
 * from [FwcBoatRampProvider]'s network call so it's unit-testable without a live/faked HTTP
 * layer - matching the split already used for NDBC/CO-OPS currents.
 */
object FwcMapper {

    /** A ramp reported as closed/removed is a real fact worth knowing, but recommending it as
     * a usable launch would be exactly the kind of unearned inference docs/PLACE_DISCOVERY.md
     * warns against - these are excluded rather than surfaced as if operational. */
    private val NON_OPERATIONAL_STATUS_MARKERS = listOf("closed", "removed", "destroyed")

    /** Builds an ArcGIS `where` clause matching [query] against name/water body/city/county -
     * single quotes are doubled (the ArcGIS/SQL escaping convention) so user input can never
     * break out of the string literal. */
    fun whereClauseFor(query: String): String {
        val escaped = query.trim().uppercase().replace("'", "''")
        return listOf("RampName", "WaterBodyName", "City", "County")
            .joinToString(" OR ") { field -> "UPPER($field) LIKE '%$escaped%'" }
    }

    fun mapCandidates(response: FwcQueryResponse): List<MarinePlaceCandidate> =
        response.features.mapNotNull { feature ->
            val a = feature.attributes
            val name = a.rampName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = a.latitude ?: return@mapNotNull null
            val lon = a.longitude ?: return@mapNotNull null
            val status = a.status.orEmpty().lowercase()
            if (NON_OPERATIONAL_STATUS_MARKERS.any { it in status }) return@mapNotNull null

            MarinePlaceCandidate(
                name = name,
                location = GeoPoint(lat, lon),
                address = listOfNotNull(a.street1, a.city, a.county?.let { "$it County, FL" }).joinToString(", ").ifBlank { null },
                guessedType = MarinePlaceType.BOAT_RAMP,
                sourceType = PlaceSourceType.FWC_BOAT_RAMP,
            )
        }
}
