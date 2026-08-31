package com.wakewindow.app.data.remote.usace

import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import java.util.Locale

/**
 * Pure query-building and DTO -> domain mapping for USACE recreation-area parcels, kept
 * separate from [UsaceRecreationProvider]'s network call for the same reason as
 * [com.wakewindow.app.data.remote.fwc.FwcMapper] - unit-testable without a live/faked HTTP
 * layer.
 */
object UsaceMapper {

    fun whereClauseFor(query: String): String {
        val escaped = query.trim().uppercase().replace("'", "''")
        return listOf("RECPROJECTSITENAME", "FEATURENAME")
            .joinToString(" OR ") { field -> "UPPER($field) LIKE '%$escaped%'" }
    }

    /**
     * The reservoir land-parcel dataset lists many small polygons per lake, so a raw mapping
     * would repeat "Table Rock Lake" a dozen times for one search - this collapses to one
     * result per distinct site name (the first, i.e. nearest-ranked, polygon ArcGIS returned
     * for it) rather than presenting duplicate places.
     */
    fun mapCandidates(response: UsaceQueryResponse): List<MarinePlaceCandidate> =
        response.features
            .mapNotNull { feature ->
                val a = feature.attributes
                val rawName = a.recProjectSiteName?.takeIf { it.isNotBlank() } ?: a.featureName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val centroid = feature.centroid ?: return@mapNotNull null
                rawName to MarinePlaceCandidate(
                    name = titleCase(rawName),
                    location = GeoPoint(centroid.y, centroid.x),
                    address = a.district?.let { "USACE $it District" },
                    guessedType = MarinePlaceType.OTHER,
                    sourceType = PlaceSourceType.USACE_RECREATION_AREA,
                )
            }
            .distinctBy { (rawName, _) -> rawName }
            .map { (_, candidate) -> candidate }

    private fun titleCase(text: String): String =
        text.lowercase(Locale.US).split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
}
