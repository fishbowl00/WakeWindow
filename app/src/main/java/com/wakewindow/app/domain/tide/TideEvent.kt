package com.wakewindow.app.domain.tide

import java.time.Instant

enum class TideEventType { HIGH, LOW }

/**
 * Always one of these five explicit states - never left null/blank for a UI to render as an
 * unexplained dash. See docs/MARINE_SCORING.md "Tide trend" for when each applies. UNKNOWN is
 * reserved for the genuine case of no bracketing tide-event data at all (e.g. the requested
 * hour falls outside the fetched day's predictions); it is not a fallback for "didn't bother
 * to compute."
 */
enum class TideTrend { RISING, FALLING, NEAR_HIGH, NEAR_LOW, UNKNOWN }

/** A single predicted high or low tide event at a specific station. */
data class TideEvent(
    val type: TideEventType,
    val time: Instant,
    val heightFt: Double,
)

/** A tide/current station, distinct concepts per docs/DATA_SOURCES.md - the nearest station
 * for tide height is not necessarily the nearest station for current. */
data class TideStation(
    val stationId: String,
    val name: String,
    val location: com.wakewindow.app.domain.model.GeoPoint,
    val distanceNm: Double,
    val datum: String,
)

data class CurrentStation(
    val stationId: String,
    val name: String,
    val location: com.wakewindow.app.domain.model.GeoPoint,
    val distanceNm: Double,
)
