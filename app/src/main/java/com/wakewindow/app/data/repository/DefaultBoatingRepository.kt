package com.wakewindow.app.data.repository

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.consensus.MarineConsensus
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.observation.MarineDisagreement
import com.wakewindow.app.domain.observation.MarineDisagreementDetector
import com.wakewindow.app.domain.observation.MarineObservationOutcome
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.observation.ObservationFreshness
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.route.BoatingPlan
import com.wakewindow.app.domain.route.BoatingRepository
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
import com.wakewindow.app.domain.scoring.ConfidenceEvidence
import com.wakewindow.app.domain.scoring.EvidenceItem
import com.wakewindow.app.domain.scoring.MarineScoreEngine
import com.wakewindow.app.domain.tide.TideEvent
import com.wakewindow.app.domain.tide.TideEventsOutcome
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStationOutcome
import com.wakewindow.app.domain.tide.TideTimeline
import com.wakewindow.app.domain.weather.ForecastOutcome
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Fans out to every configured provider concurrently, merges same-hour readings via
 * [MarineConsensus], attaches active marine alerts and tide data, folds in a nearby buoy
 * observation for near-term validation (never as a substitute forecast value - see
 * docs/MARINE_SCORING.md "Forecast vs. observation"), then hands the whole per-hour timeline
 * to [MarineScoreEngine]. One provider failing never blocks the others - see
 * docs/RIDECAST_REFERENCE_AUDIT.md section 1/11 for the pattern this follows.
 *
 * Mode A always samples the same launch location (see [BoatingPlan.defaultRouteSamples]),
 * so this fetches one location's hourly timeline rather than per-sample locations - a real
 * multi-location trip (Mode B) would extend this to fetch per distinct sample location.
 */
class DefaultBoatingRepository(
    private val generalProviders: List<GeneralWeatherProvider>,
    private val marineForecastProviders: List<MarineForecastProvider>,
    private val alertProvider: MarineAlertProvider,
    private val tideProvider: TideProvider,
    /** Nullable and entirely optional - see docs/DATA_SOURCES.md; the assessment must degrade
     * gracefully with buoy observation disabled or unreachable, never fail because of it. */
    private val observationProvider: MarineObservationProvider? = null,
) : BoatingRepository {

    /** A buoy reading is only compared against the forecast when it's within this much of the
     * departure hour - a reading from right now says nothing about a departure tomorrow
     * afternoon, and must not be presented as if it did. */
    private val nearTermObservationWindow: Duration = Duration.ofHours(3)

    override suspend fun buildAssessment(plan: BoatingPlan): BoatingWindowAssessment = coroutineScope {
        val samples = plan.defaultRouteSamples()
        val location = plan.launch.location
        val start = plan.departureTime.minus(1, ChronoUnit.HOURS)
        val end = plan.returnTime.plus(1, ChronoUnit.HOURS)
        val hours = hourlySequence(start, end)

        val generalDeferred = generalProviders.map { provider ->
            async { provider.providerName to safeCall { provider.hourlyForecast(location, start, end) } }
        }
        val marineDeferred = marineForecastProviders.map { provider ->
            async { provider.providerName to safeCall { provider.hourlyMarineForecast(location, start, end) } }
        }
        val alertsDeferred = async { safeAlertCall { alertProvider.activeAlerts(location) } }
        val tideDeferred = async { fetchTideConditions(location, start, end, hours) }
        val observationDeferred = async { fetchObservation(location) }

        val generalOutcomes = generalDeferred.awaitAll()
        val marineOutcomes = marineDeferred.awaitAll()
        val alertsOutcome = alertsDeferred.await()
        val tideResult = tideDeferred.await()
        val observationOutcome = observationDeferred.await()

        // A failed alert check is NOT the same fact as "checked, zero alerts are active" - see
        // docs/ASSESSMENT_VALIDATION.md "Missing data policy." Silently treating a failure as
        // a clean bill of health would let an actual Special Marine Warning go completely
        // unrepresented just because the alerts endpoint timed out.
        val alertCheckFailed = alertsOutcome is MarineAlertOutcome.Failure
        val alerts: List<MarineAlert> = (alertsOutcome as? MarineAlertOutcome.Success)?.alerts ?: emptyList()

        val weatherReadings: List<MarineConditions> =
            generalOutcomes.flatMap { it.second.successOrEmpty() } + marineOutcomes.flatMap { it.second.successOrEmpty() }

        val readingsByHour: Map<Instant, List<MarineConditions>> = hours.associateWith { hour ->
            weatherReadings.filter { it.timestamp == hour } + tideResult.conditions.filter { it.timestamp == hour }
        }

        val mergedByHour: MutableMap<Instant, MarineConditions?> = readingsByHour.mapValues { (hour, readings) ->
            var merged = MarineConsensus.merge(readings) ?: return@mapValues null
            val activeAlerts = alerts.filter { it.isActiveAt(hour) }
            if (activeAlerts.isNotEmpty()) merged = merged.copy(marineAlerts = activeAlerts)
            if (alertCheckFailed) {
                merged = merged.copy(
                    confidence = merged.confidence.worstOf(
                        Confidence(
                            ConfidenceLevel.MEDIUM,
                            listOf("Marine alert status could not be verified - active warnings may not be reflected"),
                        ),
                    ),
                )
            }
            merged
        }.toMutableMap()

        // Forecast vs. observation disagreement - compared against the departure hour only,
        // and only when the observation is genuinely near-term to it. This never blends the
        // observed value into the forecast series itself (see class doc).
        val departureHour = plan.departureTime.truncatedTo(ChronoUnit.HOURS)
        val disagreements: List<MarineDisagreement> = buildDisagreements(observationOutcome, mergedByHour[departureHour], plan.departureTime)
        if (disagreements.isNotEmpty()) {
            mergedByHour[departureHour] = mergedByHour[departureHour]?.let { forecast ->
                forecast.copy(
                    confidence = forecast.confidence.worstOf(
                        Confidence(ConfidenceLevel.MEDIUM, disagreements.map { it.message }),
                    ),
                )
            }
        }

        val baseAssessment = MarineScoreEngine.assess(
            samples = samples,
            conditionsFor = { sample -> nearestHourConditions(mergedByHour, sample.estimatedTime) },
            vessel = plan.vessel,
        )

        val station = (observationOutcome as? MarineObservationOutcome.Success)?.station
        val evidence = buildEvidence(generalOutcomes, marineOutcomes, tideResult, station, alertCheckFailed)

        baseAssessment.copy(
            evidence = evidence,
            nearestObservationStation = station,
            disagreements = disagreements,
        )
    }

    private suspend fun fetchObservation(location: GeoPoint): MarineObservationOutcome {
        val provider = observationProvider ?: return MarineObservationOutcome.NoStationAvailable
        return try {
            provider.nearestObservation(location)
        } catch (e: Exception) {
            MarineObservationOutcome.Failure(e.message ?: "Unknown observation provider error", e)
        }
    }

    private fun buildDisagreements(observationOutcome: MarineObservationOutcome, departureForecast: MarineConditions?, departureTime: Instant): List<MarineDisagreement> {
        val success = observationOutcome as? MarineObservationOutcome.Success ?: return emptyList()
        if (success.station.freshness == ObservationFreshness.UNUSABLE) return emptyList()
        if (departureForecast == null) return emptyList()
        val timeDelta = Duration.between(success.conditions.timestamp, departureTime).abs()
        if (timeDelta > nearTermObservationWindow) return emptyList()
        return MarineDisagreementDetector.detect(departureForecast, success.conditions)
    }

    private fun buildEvidence(
        generalOutcomes: List<Pair<String, ForecastOutcome>>,
        marineOutcomes: List<Pair<String, ForecastOutcome>>,
        tideResult: TideFetchResult,
        station: SelectedMarineStation?,
        alertCheckFailed: Boolean,
    ): ConfidenceEvidence {
        val hasGeneralForecast = generalOutcomes.any { it.second is ForecastOutcome.Success && (it.second as ForecastOutcome.Success).hourly.isNotEmpty() }
        // A provider can return a structurally successful, non-empty response for an inland
        // point (NWS's forecastGridData answers everywhere) while every wave/swell field in
        // it is null - that must not read as "marine forecast available." Require at least
        // one hour with an actual wave reading before checking this box.
        val hasMarineForecast = marineOutcomes.any { (_, outcome) ->
            (outcome as? ForecastOutcome.Success)?.hourly?.any { it.waveHeightFt != null } == true
        }
        val hasTideStation = tideResult.station != null
        val hasFreshBuoy = station != null && station.freshness != ObservationFreshness.UNUSABLE

        val items = listOf(
            EvidenceItem("NWS/general weather forecast", hasGeneralForecast),
            EvidenceItem("Marine (wind/wave) forecast", hasMarineForecast),
            EvidenceItem("NOAA tide station", hasTideStation),
            EvidenceItem("Nearby buoy observation", hasFreshBuoy),
        )

        val limitations = buildList {
            if (!hasMarineForecast) add("No marine wave/swell forecast available for this location")
            if (!hasTideStation) add("No tide station within range - this water may be non-tidal or outside NOAA CO-OPS coverage")
            if (station == null) add("No nearby buoy observation available")
            else if (station.freshness == ObservationFreshness.STALE) add("Nearest buoy observation is ${station.ageMinutes} min old - treated as historical context, not current")
            else if (station.freshness == ObservationFreshness.UNUSABLE) add("Nearest buoy observation is too old (${station.ageMinutes} min) to use")
            else if (station.distanceNm > 25.0) add("Nearest buoy observation is ${String.format("%.0f", station.distanceNm)} NM away")
            if (alertCheckFailed) add("Marine alert status could not be verified this refresh")
            add("No local current station available") // CurrentProvider has no implementation yet - see docs/ROADMAP.md
        }

        return ConfidenceEvidence(items, limitations)
    }

    private data class TideFetchResult(val conditions: List<MarineConditions>, val station: com.wakewindow.app.domain.tide.TideStation?)

    private suspend fun fetchTideConditions(location: GeoPoint, start: Instant, end: Instant, hours: List<Instant>): TideFetchResult {
        val stationOutcome = safeTideStationCall { tideProvider.nearestStation(location) }
        val station = (stationOutcome as? TideStationOutcome.Found)?.station ?: return TideFetchResult(emptyList(), null)

        val dates = datesBetween(start, end)
        val events: List<TideEvent> = dates.flatMap { date ->
            when (val outcome = safeTideEventsCall { tideProvider.events(station.stationId, date) }) {
                is TideEventsOutcome.Success -> outcome.events
                else -> emptyList()
            }
        }
        if (events.isEmpty()) return TideFetchResult(emptyList(), station)

        val source = SourceReference(
            sourceName = "NOAA Tides & Currents",
            sourceUrl = "https://tidesandcurrents.noaa.gov/stationhome.html?id=${station.stationId}",
            retrievedAt = Instant.now(),
            stationId = station.stationId,
            stationName = station.name,
            stationDistanceNm = station.distanceNm,
        )
        return TideFetchResult(TideTimeline.conditionsAt(events, hours, location, source), station)
    }

    private fun nearestHourConditions(mergedByHour: Map<Instant, MarineConditions?>, at: Instant): MarineConditions? {
        val truncated = at.truncatedTo(ChronoUnit.HOURS)
        val candidates = listOfNotNull(mergedByHour[truncated], mergedByHour[truncated.plus(1, ChronoUnit.HOURS)])
        return candidates.minByOrNull { Duration.between(it.timestamp, at).abs() }
    }

    private fun hourlySequence(start: Instant, end: Instant): List<Instant> {
        val result = mutableListOf<Instant>()
        var t = start.truncatedTo(ChronoUnit.HOURS)
        val last = end.truncatedTo(ChronoUnit.HOURS)
        while (!t.isAfter(last)) {
            result += t
            t = t.plus(1, ChronoUnit.HOURS)
        }
        return result
    }

    private fun datesBetween(start: Instant, end: Instant): List<LocalDate> {
        val zone = java.time.ZoneOffset.UTC
        var date = start.atZone(zone).toLocalDate()
        val lastDate = end.atZone(zone).toLocalDate()
        val result = mutableListOf<LocalDate>()
        while (!date.isAfter(lastDate)) {
            result += date
            date = date.plusDays(1)
        }
        return result
    }

    private suspend fun safeCall(block: suspend () -> ForecastOutcome): ForecastOutcome =
        try {
            block()
        } catch (e: Exception) {
            ForecastOutcome.Failure(e.message ?: "Unknown provider error", e)
        }

    private suspend fun safeAlertCall(block: suspend () -> MarineAlertOutcome): MarineAlertOutcome =
        try {
            block()
        } catch (e: Exception) {
            MarineAlertOutcome.Failure(e.message ?: "Unknown alert provider error", e)
        }

    private suspend fun safeTideStationCall(block: suspend () -> TideStationOutcome): TideStationOutcome =
        try {
            block()
        } catch (e: Exception) {
            TideStationOutcome.Failure(e.message ?: "Unknown tide provider error", e)
        }

    private suspend fun safeTideEventsCall(block: suspend () -> TideEventsOutcome): TideEventsOutcome =
        try {
            block()
        } catch (e: Exception) {
            TideEventsOutcome.Failure(e.message ?: "Unknown tide events error", e)
        }

    private fun ForecastOutcome.successOrEmpty(): List<MarineConditions> =
        (this as? ForecastOutcome.Success)?.hourly ?: emptyList()
}
