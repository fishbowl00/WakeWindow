package com.wakewindow.app.data.remote.nws

import com.wakewindow.app.domain.alert.AlertImpactBehavior
import com.wakewindow.app.domain.alert.AlertImpactCategory
import com.wakewindow.app.domain.alert.AlertSeverityCap
import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.alert.MarineAlertImpact
import com.wakewindow.app.domain.alert.MarineAlertSeverity
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.model.UnitConversions
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToInt

object NwsMapper {

    /** NWS timestamps carry a numeric zone offset (e.g. "-04:00"), which [Instant.parse]
     * rejects (it only accepts a literal "Z") - route every NWS instant through this. */
    fun parseNwsInstant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    /** A `forecastGridData` value's `validTime` is "<startInstant>/<ISO8601 duration>", e.g.
     * "2026-08-30T18:00:00+00:00/PT6H" - the value holds for that whole span, not just one
     * hour. See docs/DATA_SOURCES.md. */
    private fun parseInterval(validTime: String): ClosedRange<Instant> {
        val (startText, durationText) = validTime.split("/", limit = 2)
        val start = parseNwsInstant(startText)
        val duration = Duration.parse(durationText)
        return start until start.plus(duration)
    }

    private infix fun Instant.until(end: Instant): ClosedRange<Instant> =
        this..end.minusNanos(1)

    private fun valueAt(quantity: NwsGridQuantitative?, at: Instant): Double? {
        if (quantity == null) return null
        for (entry in quantity.values) {
            val value = entry.value ?: continue
            val interval = try {
                parseInterval(entry.validTime)
            } catch (e: Exception) {
                continue
            }
            if (at in interval) return value
        }
        return null
    }

    fun mapGridpointsToMarineConditions(
        properties: NwsGridpointsProperties,
        hours: List<Instant>,
        location: GeoPoint,
        source: SourceReference,
    ): List<MarineConditions> = hours.map { hour ->
        val windKmh = valueAt(properties.windSpeed, hour)
        val gustKmh = valueAt(properties.windGust, hour)
        val tempC = valueAt(properties.temperature, hour)
        val apparentC = valueAt(properties.apparentTemperature, hour)
        val visM = valueAt(properties.visibility, hour)
        val waveM = valueAt(properties.waveHeight, hour)
        val swellM = valueAt(properties.primarySwellHeight, hour)
        val precip = valueAt(properties.probabilityOfPrecipitation, hour)
        val thunder = valueAt(properties.probabilityOfThunder, hour)

        MarineConditions(
            timestamp = hour,
            location = location,
            sustainedWindKts = windKmh?.let { UnitConversions.kmhToKnots(it) },
            windDirectionDeg = valueAt(properties.windDirection, hour),
            gustKts = gustKmh?.let { UnitConversions.kmhToKnots(it) },
            precipitationProbabilityPercent = precip?.roundToInt(),
            thunderstormProbabilityPercent = thunder?.roundToInt(),
            visibilityNm = visM?.let { it / 1852.0 },
            airTemperatureF = tempC?.let { UnitConversions.celsiusToFahrenheit(it) },
            apparentTemperatureF = apparentC?.let { UnitConversions.celsiusToFahrenheit(it) },
            waveHeightFt = waveM?.let { UnitConversions.metersToFeet(it) },
            waveDirectionDeg = valueAt(properties.waveDirection, hour),
            wavePeriodSec = valueAt(properties.wavePeriod, hour),
            swellHeightFt = swellM?.let { UnitConversions.metersToFeet(it) },
            swellDirectionDeg = valueAt(properties.primarySwellDirection, hour),
            source = source,
            confidence = Confidence.high(),
        )
    }

    fun mapAlerts(response: NwsAlertsResponse): List<MarineAlert> =
        response.features.mapNotNull { feature ->
            val p = feature.properties
            val event = p.event ?: return@mapNotNull null
            val classification = classify(event)
            MarineAlert(
                id = p.id ?: event,
                event = event,
                headline = p.headline,
                severity = classification.severity,
                effective = (p.onset ?: p.effective)?.let { runCatching { parseNwsInstant(it) }.getOrNull() },
                expires = (p.ends ?: p.expires)?.let { runCatching { parseNwsInstant(it) }.getOrNull() },
                areaDescription = p.areaDesc,
                vesselSizeExemptApplicable = classification.vesselSizeExemptApplicable,
                impact = classification.impact,
            )
        }

    data class AlertClassification(
        val severity: MarineAlertSeverity,
        val vesselSizeExemptApplicable: Boolean,
        val impact: MarineAlertImpact,
    )

    /**
     * Classifies by event-text substring, matching RideCast's own hazard-tiering approach
     * (see docs/RIDECAST_REFERENCE_AUDIT.md section 7), extended with marine event types and
     * the [MarineAlertImpact] relevance model - see docs/MARINE_SCORING.md "Alert relevance
     * model" for the full rationale table and why this replaces Sprint 2's blanket
     * "any advisory/warning caps the category" policy: a Heat Advisory and a Small Craft
     * Advisory are not the same kind of consequence, and treating them identically was a real
     * gap this table now fixes.
     *
     * **`vesselSizeExemptApplicable` is computed but no longer consulted by scoring** - see
     * [MarineAlert]'s doc comment. A Small Craft Advisory is always surfaced and always applies
     * its category ceiling.
     */
    fun classify(event: String): AlertClassification {
        val e = event.lowercase()
        return when {
            "hurricane" in e || "tropical storm" in e ->
                extreme(MarineAlertSeverity.EXTREME, AlertImpactCategory.SEVERE_WEATHER)
            "special marine warning" in e ->
                extreme(MarineAlertSeverity.EXTREME, AlertImpactCategory.MARINE_NAVIGATION)
            "severe thunderstorm warning" in e || ("tornado" in e && "warning" in e) ->
                // A confirmed, currently-occurring severe convective cell (near-gale gusts +
                // lightning) is treated as seriously as a formal marine warning - a much
                // stronger signal than the probabilistic thunderstorm forecast already scored
                // separately in MarinePointScorer.
                extreme(MarineAlertSeverity.EXTREME, AlertImpactCategory.SEVERE_WEATHER)
            "gale" in e || "storm warning" in e ->
                AlertClassification(
                    MarineAlertSeverity.SEVERE, false,
                    MarineAlertImpact(AlertImpactCategory.MARINE_NAVIGATION, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.POOR),
                )
            "small craft advisory" in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, vesselSizeExemptApplicable = true,
                    MarineAlertImpact(AlertImpactCategory.MARINE_NAVIGATION, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.CAUTION),
                )
            "dense fog" in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, false,
                    MarineAlertImpact(AlertImpactCategory.MARINE_NAVIGATION, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.CAUTION),
                )
            // Deliberately excludes "watch" - a not-yet-occurring Coastal Flood Watch belongs
            // to the generic watch handling below (matching existing severity classification),
            // while an active Advisory/Warning is a real, current access impact.
            "coastal flood" in e && "watch" !in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, false,
                    MarineAlertImpact(AlertImpactCategory.COASTAL_ACCESS, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.CAUTION),
                )
            "excessive heat warning" in e ->
                AlertClassification(
                    MarineAlertSeverity.SEVERE, false,
                    MarineAlertImpact(AlertImpactCategory.HUMAN_EXPOSURE, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.CAUTION),
                )
            "heat advisory" in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, false,
                    MarineAlertImpact(AlertImpactCategory.HUMAN_EXPOSURE, AlertImpactBehavior.SCORE_DEDUCTION, scoreDeduction = 15.0),
                )
            "wind chill" in e || "freeze" in e || "frost" in e || "cold weather" in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, false,
                    MarineAlertImpact(AlertImpactCategory.HUMAN_EXPOSURE, AlertImpactBehavior.SCORE_DEDUCTION, scoreDeduction = 10.0),
                )
            "air quality" in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, false,
                    MarineAlertImpact(AlertImpactCategory.INFORMATIONAL, AlertImpactBehavior.INFORMATIONAL_ONLY),
                )
            "watch" in e ->
                AlertClassification(
                    MarineAlertSeverity.WATCH, false,
                    MarineAlertImpact(AlertImpactCategory.SEVERE_WEATHER, AlertImpactBehavior.SCORE_DEDUCTION, scoreDeduction = 5.0),
                )
            // An unrecognized *warning*-tier alert - NWS reserves "Warning" for its more
            // serious products in general, so this still applies a moderate ceiling rather
            // than being ignored, without assuming it's marine-navigation-specific.
            "warning" in e ->
                AlertClassification(
                    MarineAlertSeverity.SEVERE, false,
                    MarineAlertImpact(AlertImpactCategory.UNKNOWN, AlertImpactBehavior.CATEGORY_CEILING, AlertSeverityCap.CAUTION),
                )
            // An unrecognized *advisory*-tier alert - real, worth a deduction, but not treated
            // as equivalent to a marine emergency just because the word "advisory" appears.
            "advisory" in e ->
                AlertClassification(
                    MarineAlertSeverity.ADVISORY, false,
                    MarineAlertImpact(AlertImpactCategory.UNKNOWN, AlertImpactBehavior.SCORE_DEDUCTION, scoreDeduction = 8.0),
                )
            else ->
                AlertClassification(
                    MarineAlertSeverity.UNKNOWN, false,
                    MarineAlertImpact(AlertImpactCategory.UNKNOWN, AlertImpactBehavior.INFORMATIONAL_ONLY),
                )
        }
    }

    private fun extreme(severity: MarineAlertSeverity, category: AlertImpactCategory) = AlertClassification(
        severity, false,
        MarineAlertImpact(category, AlertImpactBehavior.HARD_GATE, AlertSeverityCap.NO_GO),
    )

    /** Kept for source compatibility with existing call sites/tests that only need the tier. */
    fun classifySeverity(event: String): MarineAlertSeverity = classify(event).severity
}
