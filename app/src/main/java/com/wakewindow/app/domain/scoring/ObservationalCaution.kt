package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.observation.DisagreementType
import com.wakewindow.app.domain.observation.ObservationForecastComparison
import com.wakewindow.app.domain.observation.ObservationFreshness
import com.wakewindow.app.domain.observation.RepresentativenessLevel
import com.wakewindow.app.domain.vessel.VesselProfile
import java.time.Duration
import java.time.Instant

/**
 * Decides whether a station observation that's already been compared against a forecast **for
 * that station's own coordinates** (see [ObservationForecastComparison]) should influence the
 * near-term (departure-hour) assessment - and if so, how. See docs/MARINE_SCORING.md
 * "Observation influence on assessment."
 *
 * This never blends the observed value into the forecast timeline (observations describe NOW,
 * forecasts describe LATER - see docs/MARINE_SCORING.md "Forecast vs. observation"). Instead it
 * produces an explicit [Hazard] with a category ceiling, exactly like a marine alert gate,
 * applied only to the departure point and only when the evidence is fresh, representative, and
 * genuinely worse than what was forecast.
 */
object ObservationalCautionEvaluator {

    /** An observation more than this far from the planned departure says nothing useful about
     * conditions the boater will actually experience at departure. */
    private val NEAR_TERM_WINDOW: Duration = Duration.ofHours(3)

    fun evaluate(comparison: ObservationForecastComparison?, departureTime: Instant, vessel: VesselProfile): Hazard? {
        if (comparison == null) return null
        if (comparison.representativeness.level != RepresentativenessLevel.HIGH && comparison.representativeness.level != RepresentativenessLevel.MEDIUM) return null
        if (Duration.between(comparison.observedAt, departureTime).abs() > NEAR_TERM_WINDOW) return null

        // Only a "conditions are worse than forecast" signal warrants a caution - a materially
        // *better*-than-forecast observation, or an unrelated difference (temperature), is not
        // a safety concern and must not gate anything.
        val worseWave = comparison.disagreements.firstOrNull { it.type == DisagreementType.WAVE_HEIGHT && it.observedValue > it.forecastValue }
        val worseGust = comparison.disagreements.firstOrNull { it.type == DisagreementType.GUST && it.observedValue > it.forecastValue }
        val worseWind = comparison.disagreements.firstOrNull { it.type == DisagreementType.WIND && it.observedValue > it.forecastValue }
        val worseVisibility = comparison.disagreements.firstOrNull { it.type == DisagreementType.VISIBILITY && it.observedValue < it.forecastValue }

        val worst = listOfNotNull(worseWave, worseGust, worseWind, worseVisibility)
        if (worst.isEmpty()) return null

        val severe = worst.any { d ->
            when (d.type) {
                DisagreementType.WAVE_HEIGHT -> d.observedValue >= vessel.waveToleranceFt
                DisagreementType.GUST -> d.observedValue >= vessel.gustToleranceKts
                DisagreementType.WIND -> d.observedValue >= vessel.windToleranceKts
                DisagreementType.VISIBILITY -> d.observedValue < vessel.visibilityToleranceNm / 2
                else -> false
            }
        }
        val cap = if (severe) BoatingCategory.POOR else BoatingCategory.CAUTION

        val headline = worst.first()
        val message = "Observed conditions at ${comparison.station.name ?: comparison.station.stationId} are currently worse than forecast: ${headline.message}"

        return Hazard(
            type = HazardType.OBSERVED_CONDITIONS,
            message = message,
            at = departureTime,
            value = headline.observedValue,
            threshold = headline.forecastValue,
            categoryCap = cap,
        )
    }
}
