package com.wakewindow.app.domain.observation

import com.wakewindow.app.domain.marine.MarineConditions

enum class DisagreementType { WIND, GUST, WAVE_HEIGHT, VISIBILITY, TEMPERATURE, OTHER }

/**
 * A material difference between what the forecast says and what a nearby station is
 * currently observing - e.g. forecast wind 10 kt vs. an observed 19 kt. See
 * docs/MARINE_SCORING.md "Forecast vs. observation disagreement." Detected only for the
 * departure-hour forecast against a genuinely current (non-stale) observation - comparing a
 * buoy reading to a forecast hour eight hours away would conflate "now" with "later," which
 * is exactly the mistake this concept exists to avoid.
 */
data class MarineDisagreement(
    val type: DisagreementType,
    val forecastValue: Double,
    val observedValue: Double,
    val message: String,
)

/**
 * Meaningful-difference thresholds, deliberately few and coarse for this sprint rather than a
 * finely-tuned per-condition table - see docs/MARINE_SCORING.md for the rationale to keep
 * this simple until real-world discrepancy data justifies more nuance.
 */
object MarineDisagreementDetector {
    private const val WIND_THRESHOLD_KTS = 8.0
    private const val GUST_THRESHOLD_KTS = 10.0
    private const val WAVE_HEIGHT_THRESHOLD_FT = 1.5
    private const val VISIBILITY_THRESHOLD_NM = 1.0
    private const val TEMPERATURE_THRESHOLD_F = 10.0

    /** Compares a forecast reading against a fresh-enough observation at (approximately) the
     * same place. Returns one entry per field with a material difference - never fabricates a
     * disagreement from a field either side lacks. */
    fun detect(forecast: MarineConditions, observation: MarineConditions): List<MarineDisagreement> {
        val results = mutableListOf<MarineDisagreement>()

        compare(forecast.sustainedWindKts, observation.sustainedWindKts, WIND_THRESHOLD_KTS)?.let { (f, o) ->
            results += MarineDisagreement(
                DisagreementType.WIND, f, o,
                "Observed wind (${o.fmt()} kt) is materially different from the forecast (${f.fmt()} kt)",
            )
        }
        compare(forecast.gustKts, observation.gustKts, GUST_THRESHOLD_KTS)?.let { (f, o) ->
            results += MarineDisagreement(
                DisagreementType.GUST, f, o,
                "Observed gusts (${o.fmt()} kt) are materially different from the forecast (${f.fmt()} kt)",
            )
        }
        compare(forecast.waveHeightFt, observation.waveHeightFt, WAVE_HEIGHT_THRESHOLD_FT)?.let { (f, o) ->
            val direction = if (o > f) "higher" else "lower"
            results += MarineDisagreement(
                DisagreementType.WAVE_HEIGHT, f, o,
                "Observed seas are currently $direction than forecast (${o.fmt()} ft observed vs. ${f.fmt()} ft forecast)",
            )
        }
        compare(forecast.visibilityNm, observation.visibilityNm, VISIBILITY_THRESHOLD_NM)?.let { (f, o) ->
            results += MarineDisagreement(
                DisagreementType.VISIBILITY, f, o,
                "Observed visibility (${o.fmt()} NM) differs materially from the forecast (${f.fmt()} NM)",
            )
        }
        compare(forecast.airTemperatureF, observation.airTemperatureF, TEMPERATURE_THRESHOLD_F)?.let { (f, o) ->
            results += MarineDisagreement(
                DisagreementType.TEMPERATURE, f, o,
                "Observed air temperature (${o.fmt()}°F) differs materially from the forecast (${f.fmt()}°F)",
            )
        }

        return results
    }

    private fun compare(forecastValue: Double?, observedValue: Double?, threshold: Double): Pair<Double, Double>? {
        if (forecastValue == null || observedValue == null) return null
        return if (kotlin.math.abs(forecastValue - observedValue) >= threshold) forecastValue to observedValue else null
    }

    private fun Double.fmt(): String = String.format("%.1f", this)
}
