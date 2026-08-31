package com.wakewindow.app.domain.weather

import com.wakewindow.app.domain.marine.MarineConditions

/**
 * Outcome of fetching an hourly forecast series from one provider (general weather or
 * marine). A dedicated sealed type rather than a nullable/generic Result - "no coverage here"
 * (e.g. an inland lake has no marine forecast at all) is a different, permanent fact from
 * "the provider call failed," and callers (confidence calculation especially) need to tell
 * them apart. See docs/RIDECAST_REFERENCE_AUDIT.md section 1 on RideCast's per-use-case
 * sealed-outcome discipline.
 */
sealed interface ForecastOutcome {
    data class Success(val hourly: List<MarineConditions>) : ForecastOutcome
    data class Unavailable(val reason: String) : ForecastOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : ForecastOutcome
}

fun ForecastOutcome.hourlyOrEmpty(): List<MarineConditions> =
    (this as? ForecastOutcome.Success)?.hourly ?: emptyList()
