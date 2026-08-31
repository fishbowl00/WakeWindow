package com.wakewindow.app.domain.observation

import java.time.Instant

/** Why a comparison could or couldn't be made - a comparison that wasn't attempted or wasn't
 * possible is a different, honest fact from "attempted and found no disagreement." */
enum class ComparisonStatus {
    /** Forecast-at-station and observation were both available and compared. */
    COMPARABLE,
    /** No forecast could be resolved for the station's own coordinates. */
    NO_FORECAST_AT_STATION,
    /** A forecast-at-station value exists but not close enough in time to the observation to compare. */
    TIME_MISALIGNED,
    /** No observation/station was available at all - comparison never attempted. */
    NOT_ATTEMPTED,
}

/**
 * The result of comparing a buoy/station observation against the forecast **for that same
 * station's coordinates** - never against a forecast for the launch or any other point. See
 * docs/MARINE_SCORING.md "Forecast vs. observation" for why spatial mismatch was a real bug in
 * Sprint 2: a 23 NM offshore buoy reading is not evidence about the launch location's forecast
 * unless the two are compared at the buoy's own position.
 */
data class ObservationForecastComparison(
    val station: SelectedMarineStation,
    val representativeness: StationRepresentativeness,
    val observedAt: Instant,
    val forecastValidAt: Instant?,
    val disagreements: List<MarineDisagreement>,
    val status: ComparisonStatus,
)
