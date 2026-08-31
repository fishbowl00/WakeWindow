package com.wakewindow.app.domain.route

import com.wakewindow.app.domain.model.GeoPoint
import java.time.Instant

/**
 * Generalizes RideCast's fixed 4-point HOME/ROUTE_33/ROUTE_66/WORK sampling into an
 * open-ended list, since a boating outing isn't a symmetric there-and-back with exactly two
 * legs - see docs/RIDECAST_REFERENCE_AUDIT.md section 3. Mode A (return to same launch) uses
 * three or more samples across the outing; Mode B (port-to-port, future) can use as many
 * waypoints as a real route needs.
 */
enum class RouteSampleRole {
    DEPARTURE,
    UNDERWAY,
    WAYPOINT,
    DESTINATION,
    RETURN,
}

data class RouteSample(
    val location: GeoPoint,
    val role: RouteSampleRole,
    /** Fraction of the outing elapsed at this sample, 0.0 at departure to 1.0 at return/destination. */
    val progressFraction: Double,
    val estimatedTime: Instant,
)
