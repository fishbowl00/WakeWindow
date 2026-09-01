package com.wakewindow.app.domain.trip

import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import java.time.Duration
import java.time.Instant

/**
 * Generates additional weather-only sample points along a long [TripLeg] - see docs/TRIP_PLANNING.md
 * "Intermediate route sampling." A generated sample is deterministic (distance/time-derived, never
 * randomized) and carries [RouteSampleRole.WEATHER_SAMPLE], never [RouteSampleRole.WAYPOINT] - it
 * is not a point the user chose and must never be presented as a recommended or navigable stop,
 * only as an additional weather-evaluation point between two real planning points.
 *
 * Deliberately conservative: a short leg gets no intermediate sample at all (endpoints alone are
 * already representative at that scale), and even a very long leg is capped at
 * [TripPlanLimits.MAX_WEATHER_SAMPLES_PER_LEG] rather than sampling continuously - see the sprint
 * brief's own "do not oversample blindly."
 */
object WeatherSampleGenerator {

    /** Legs shorter than this get no intermediate sample - the two endpoints already describe
     * conditions at this scale well enough that a third sample adds fetch cost without adding
     * real information. */
    private const val MIN_NM_FOR_ONE_SAMPLE = 15.0

    /** One additional sample is added for roughly every this many nautical miles beyond the
     * first threshold, up to [TripPlanLimits.MAX_WEATHER_SAMPLES_PER_LEG]. */
    private const val NM_PER_ADDITIONAL_SAMPLE = 25.0

    /**
     * Deterministic sample count and time-aware interpolation for one leg. Locations are
     * interpolated along the great-circle line between [from] and [to] (see
     * [com.wakewindow.app.domain.model.GeoPoint.interpolateTo]) - a straight-line interpolation,
     * exactly as honest about non-navigability as [TripLeg.planningDistanceNm] itself. Times are
     * interpolated the same way across [legStart]..[legEnd] (the previous point's own time to
     * this leg's estimated arrival), so a sample partway along a leg gets a genuinely different
     * expected time than either endpoint, not the departure-hour forecast reused everywhere.
     */
    fun samplesFor(leg: TripLeg, legStart: Instant): List<RouteSample> {
        val distanceNm = leg.planningDistanceNm
        if (distanceNm < MIN_NM_FOR_ONE_SAMPLE) return emptyList()

        val extraSamples = ((distanceNm - MIN_NM_FOR_ONE_SAMPLE) / NM_PER_ADDITIONAL_SAMPLE).toInt()
        val count = (1 + extraSamples).coerceAtMost(TripPlanLimits.MAX_WEATHER_SAMPLES_PER_LEG)

        val legEnd = leg.estimatedArrival
        val durationMillis = Duration.between(legStart, legEnd).toMillis().coerceAtLeast(0L)

        return (1..count).map { i ->
            val fraction = i.toDouble() / (count + 1)
            RouteSample(
                location = leg.from.location.interpolateTo(leg.to.location, fraction),
                role = RouteSampleRole.WEATHER_SAMPLE,
                progressFraction = fraction,
                estimatedTime = legStart.plusMillis((durationMillis * fraction).toLong()),
            )
        }
    }
}
