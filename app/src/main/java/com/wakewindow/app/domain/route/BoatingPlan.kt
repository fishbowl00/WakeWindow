package com.wakewindow.app.domain.route

import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Instant
import java.time.ZoneId

/**
 * Mode A: a single boating day, returning to the same launch. [departureTime]/[returnTime]
 * are the user's planned times; the whole window between them is what gets assessed - not
 * just the departure instant. See docs/PRODUCT.md "Mode A."
 */
data class BoatingPlan(
    val launch: MarinePlace,
    val departureTime: Instant,
    val returnTime: Instant,
    val vessel: VesselProfile,
    /** Resolved from the launch location, not the device - see docs/ARCHITECTURE.md "Time zone handling." */
    val zoneId: ZoneId,
) {
    init {
        require(returnTime.isAfter(departureTime)) { "returnTime must be after departureTime" }
    }

    val durationMinutes: Long get() = java.time.Duration.between(departureTime, returnTime).toMinutes()

    /** Route samples across the outing: departure and return at the launch, evenly-spaced
     * underway samples in between (hourly, capped to a reasonable count). A future Mode B
     * trip plan would supply real waypoint geometry instead of this same-point round trip. */
    fun defaultRouteSamples(maxUnderwaySamples: Int = 12): List<RouteSample> {
        val totalMinutes = durationMinutes.coerceAtLeast(1)
        val hourlySampleCount = (totalMinutes / 60).toInt().coerceIn(0, maxUnderwaySamples)

        val samples = mutableListOf(
            RouteSample(launch.location, RouteSampleRole.DEPARTURE, 0.0, departureTime),
        )
        if (hourlySampleCount > 0) {
            for (i in 1..hourlySampleCount) {
                val fraction = i.toDouble() / (hourlySampleCount + 1)
                val time = departureTime.plusSeconds((totalMinutes * 60 * fraction).toLong())
                samples += RouteSample(launch.location, RouteSampleRole.UNDERWAY, fraction, time)
            }
        }
        samples += RouteSample(launch.location, RouteSampleRole.RETURN, 1.0, returnTime)
        return samples
    }
}
