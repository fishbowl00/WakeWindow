package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.model.SourceType
import com.wakewindow.app.domain.place.FacilityOperationalStatus
import com.wakewindow.app.domain.place.MarineFacilityInfo
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import java.time.Instant

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
     * break out of the string literal. [normalizeAbbreviations] expands common city-name
     * abbreviations first - confirmed live 2026-08-31 that FWC's own `City` field always spells
     * "Saint"/"Fort" out in full (`SAINT PETERSBURG`, `FORT MYERS`, `FORT LAUDERDALE`, ...),
     * so a plain substring match against a user-typed "St Petersburg" or "Ft Myers" - a natural,
     * common way to type either city - would otherwise silently return zero FWC ramps and fall
     * through entirely to the unranked Photon fallback. */
    fun whereClauseFor(query: String): String {
        val escaped = normalizeAbbreviations(query.trim().uppercase()).replace("'", "''")
        return listOf("RampName", "WaterBodyName", "City", "County")
            .joinToString(" OR ") { field -> "UPPER($field) LIKE '%$escaped%'" }
    }

    private val ST_ABBREVIATION = Regex("\\bST\\b")
    private val FT_ABBREVIATION = Regex("\\bFT\\b")

    private fun normalizeAbbreviations(uppercasedQuery: String): String =
        uppercasedQuery.replace(".", "")
            .replace(ST_ABBREVIATION, "SAINT")
            .replace(FT_ABBREVIATION, "FORT")

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
                sourceId = a.objectId?.toString(),
            )
        }

    /** Builds an exact-match `where` clause for re-fetching a single known record by its
     * ArcGIS `OBJECTID` - used by [FwcFacilityInfoProvider] instead of a fuzzy name match
     * whenever [MarinePlaceCandidate.sourceId] is available. */
    fun whereClauseForObjectId(objectId: String): String = "OBJECTID=$objectId"

    /** Fallback exact-name match for a candidate saved before [MarinePlaceCandidate.sourceId]
     * existed, or if a future record legitimately lacks one - a real possibility for any
     * long-lived saved launch, not a hypothetical. */
    fun whereClauseForExactName(name: String): String {
        val escaped = name.trim().uppercase().replace("'", "''")
        return "UPPER(RampName)='$escaped'"
    }

    /**
     * Maps FWC's own ramp fields into [MarineFacilityInfo] - see docs/DATA_SOURCES.md "Marine
     * place / launch intelligence." Only fields the dataset actually populates are set; every
     * other [MarineFacilityInfo] field is left at its `UNKNOWN`/null default rather than
     * guessed - FWC's schema simply doesn't publish ramp lanes, gate hours, VHF channel, etc.
     * as separate structured fields, so those stay honestly unknown until a source that does
     * publish them is wired in.
     */
    fun toFacilityInfo(attributes: FwcAttributes, retrievedAt: Instant = Instant.now()): MarineFacilityInfo {
        val a = attributes
        return MarineFacilityInfo(
            phone = a.contactPhone?.takeIf { it.isNotBlank() && !it.equals("NA", ignoreCase = true) },
            waterBodyName = a.waterBodyName?.takeIf { it.isNotBlank() },
            rampLanes = a.totalLanes,
            rampType = a.rampType?.takeIf { it.isNotBlank() },
            accessType = a.accessType?.takeIf { it.isNotBlank() },
            amenitiesRaw = a.amenities?.takeIf { it.isNotBlank() },
            operationalStatus = operationalStatusOf(a.status),
            operationalStatusRaw = a.status?.takeIf { it.isNotBlank() },
            source = SourceReference(
                sourceName = "Florida FWC Boat Ramp Inventory",
                sourceUrl = "https://myfwc.com/boating/boat-ramps/",
                retrievedAt = retrievedAt,
                recordId = a.objectId?.toString(),
                sourceType = SourceType.STATE_AGENCY,
                isOfficial = true,
            ),
        )
    }

    /** Classifies FWC's free-text `Status` field - confirmed live values (Sprint 3) include
     * "Open for Business" and "Temporarily Closed"; anything not recognized stays [UNKNOWN]
     * rather than guessed, since the dataset's full status vocabulary isn't itself verified. */
    fun operationalStatusOf(status: String?): FacilityOperationalStatus {
        val normalized = status?.trim()?.lowercase() ?: return FacilityOperationalStatus.UNKNOWN
        return when {
            "open" in normalized -> FacilityOperationalStatus.OPEN
            "temporarily closed" in normalized -> FacilityOperationalStatus.CLOSED
            "seasonal" in normalized -> FacilityOperationalStatus.SEASONAL
            "closed" in normalized || "removed" in normalized || "destroyed" in normalized -> FacilityOperationalStatus.CLOSED
            else -> FacilityOperationalStatus.UNKNOWN
        }
    }
}
