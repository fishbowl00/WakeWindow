package com.wakewindow.app.data.remote.coops

import com.wakewindow.app.domain.tide.CurrentEvent
import com.wakewindow.app.domain.tide.CurrentEventType
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Pure DTO -> domain mapping for NOAA CO-OPS current predictions, kept separate from
 * [CoopsCurrentProvider]'s network call so it's unit-testable without a live/faked HTTP layer -
 * matching the split already used for NDBC (see `NdbcObservationParser`).
 */
object CoopsCurrentMapper {

    /**
     * `Velocity_Major` is signed (positive = flood, negative = ebb, ~0 = slack); the domain
     * [CurrentEvent.speedKts] is always a magnitude, with the sign captured instead by
     * [CurrentEventType] and by which of `meanFloodDir`/`meanEbbDir` applies. A row whose
     * `Time` can't be parsed, or whose `Type` isn't one of "flood"/"ebb"/"slack", is dropped
     * rather than guessed at.
     */
    fun mapEvents(response: CoopsCurrentPredictionsResponse): List<CurrentEvent> =
        response.current_predictions?.cp.orEmpty().mapNotNull { dto ->
            val time = runCatching { LocalDateTime.parse(dto.time.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
                ?: return@mapNotNull null
            when (dto.type.lowercase()) {
                "flood" -> CurrentEvent(CurrentEventType.FLOOD_MAX, time, abs(dto.velocityMajor), dto.meanFloodDir)
                "ebb" -> CurrentEvent(CurrentEventType.EBB_MAX, time, abs(dto.velocityMajor), dto.meanEbbDir)
                "slack" -> CurrentEvent(CurrentEventType.SLACK, time, 0.0, null)
                else -> null
            }
        }
}
