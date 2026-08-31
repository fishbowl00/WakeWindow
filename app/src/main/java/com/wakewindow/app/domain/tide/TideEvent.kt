package com.wakewindow.app.domain.tide

import java.time.Instant

enum class TideEventType { HIGH, LOW }

enum class TideTrend { RISING, FALLING, SLACK_AT_HIGH, SLACK_AT_LOW }

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
