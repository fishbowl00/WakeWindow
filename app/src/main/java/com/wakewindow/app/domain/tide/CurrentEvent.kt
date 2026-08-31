package com.wakewindow.app.domain.tide

import java.time.Instant

/**
 * The overwhelming majority of NOAA CO-OPS current stations are harmonic (subordinate)
 * stations that only predict the moments current *turns* - maximum flood, maximum ebb, and
 * slack water in between - not a continuous speed curve. Modeling exactly those three moments
 * (rather than pretending a continuous prediction this data doesn't support) is what "ebb/
 * flood/slack interpretation where defensible" means in docs/DATA_SOURCES.md "Current
 * predictions."
 */
enum class CurrentEventType { FLOOD_MAX, EBB_MAX, SLACK }

/**
 * A single predicted current turn at a station. [directionDeg] is null at [CurrentEventType.SLACK]
 * - direction is genuinely undefined at zero velocity, never a fabricated value.
 */
data class CurrentEvent(
    val type: CurrentEventType,
    val time: Instant,
    val speedKts: Double,
    val directionDeg: Double?,
)

sealed interface CurrentEventsOutcome {
    data class Success(val events: List<CurrentEvent>) : CurrentEventsOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : CurrentEventsOutcome
}
