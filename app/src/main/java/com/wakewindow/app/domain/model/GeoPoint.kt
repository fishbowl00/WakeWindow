package com.wakewindow.app.domain.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A latitude/longitude pair. Never carries a name or address - see [com.wakewindow.app.domain.place.MarinePlace]. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }

    /** Great-circle distance in nautical miles (haversine). */
    fun distanceNmTo(other: GeoPoint): Double {
        val r = 3440.065 // Earth radius in nautical miles
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** A point on the great-circle line toward [other], at [fraction] of the total distance (0.0-1.0). */
    fun interpolateTo(other: GeoPoint, fraction: Double): GeoPoint {
        require(fraction in 0.0..1.0) { "fraction out of range: $fraction" }
        return GeoPoint(
            latitude = latitude + (other.latitude - latitude) * fraction,
            longitude = longitude + (other.longitude - longitude) * fraction,
        )
    }
}
