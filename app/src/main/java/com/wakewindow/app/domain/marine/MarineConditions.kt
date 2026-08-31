package com.wakewindow.app.domain.marine

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.tide.CurrentEvent
import com.wakewindow.app.domain.tide.TideEvent
import com.wakewindow.app.domain.tide.TideTrend
import java.time.Instant

/**
 * Marine + general weather conditions at one place and time. Every field beyond
 * [timestamp]/[location]/[source] is nullable by design - a marine station or forecast grid
 * will not supply every field everywhere (an inland lake has no tide or swell; a sparse
 * offshore grid may lack visibility). A null field means "not available," never "assume
 * zero/calm." See docs/PRODUCT.md "Marine conditions domain" and docs/MARINE_SCORING.md.
 */
data class MarineConditions(
    val timestamp: Instant,
    val location: GeoPoint,

    val sustainedWindKts: Double? = null,
    val windDirectionDeg: Double? = null,
    val gustKts: Double? = null,

    val precipitationProbabilityPercent: Int? = null,
    val thunderstormProbabilityPercent: Int? = null,
    val visibilityNm: Double? = null,

    val airTemperatureF: Double? = null,
    val apparentTemperatureF: Double? = null,
    val waterTemperatureF: Double? = null,

    val waveHeightFt: Double? = null,
    val waveDirectionDeg: Double? = null,
    val wavePeriodSec: Double? = null,
    val swellHeightFt: Double? = null,
    val swellDirectionDeg: Double? = null,
    val swellPeriodSec: Double? = null,

    val tideHeightFt: Double? = null,
    val tideTrend: TideTrend? = null,
    val nextHighTide: TideEvent? = null,
    val nextLowTide: TideEvent? = null,

    val currentSpeedKts: Double? = null,
    val currentDirectionDeg: Double? = null,
    /** The next predicted flood/ebb max or slack turn after [timestamp] - see [CurrentEvent].
     * Null whenever no current station applies here at all, exactly like [nextHighTide]. */
    val nextCurrentEvent: CurrentEvent? = null,

    val marineAlerts: List<MarineAlert> = emptyList(),

    val source: SourceReference,
    /** Non-null only when this reading is an observation, not a forecast value. */
    val observationAgeMinutes: Int? = null,
    val confidence: Confidence,
) {
    val isObservation: Boolean get() = observationAgeMinutes != null
    val hasAnyMarineData: Boolean get() =
        waveHeightFt != null || swellHeightFt != null || tideHeightFt != null || currentSpeedKts != null
}
