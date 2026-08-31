package com.wakewindow.app.data.remote.nws

import com.wakewindow.app.domain.alert.MarineAlert
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
            MarineAlert(
                id = p.id ?: event,
                event = event,
                headline = p.headline,
                severity = classifySeverity(event),
                effective = (p.onset ?: p.effective)?.let { runCatching { parseNwsInstant(it) }.getOrNull() },
                expires = (p.ends ?: p.expires)?.let { runCatching { parseNwsInstant(it) }.getOrNull() },
                areaDescription = p.areaDesc,
            )
        }

    /** Classifies by event-text substring, matching RideCast's own hazard-tiering approach
     * (see docs/RIDECAST_REFERENCE_AUDIT.md section 7), extended with marine event types. */
    fun classifySeverity(event: String): MarineAlertSeverity {
        val e = event.lowercase()
        return when {
            "hurricane" in e || "tropical storm" in e || "special marine warning" in e -> MarineAlertSeverity.EXTREME
            "gale" in e || "storm warning" in e -> MarineAlertSeverity.SEVERE
            "small craft advisory" in e -> MarineAlertSeverity.ADVISORY
            "watch" in e -> MarineAlertSeverity.WATCH
            "warning" in e -> MarineAlertSeverity.SEVERE
            "advisory" in e -> MarineAlertSeverity.ADVISORY
            else -> MarineAlertSeverity.UNKNOWN
        }
    }
}
