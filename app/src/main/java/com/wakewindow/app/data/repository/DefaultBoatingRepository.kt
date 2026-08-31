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
import com.wakewindow.app.domain.observation.ComparisonStatus
import com.wakewindow.app.domain.observation.MarineDisagreementDetector
import com.wakewindow.app.domain.observation.MarineObservationOutcome
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.observation.ObservationForecastComparison
import com.wakewindow.app.domain.observation.ObservationFreshness
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.observation.StationRepresentativenessEvaluator
import com.wakewindow.app.domain.observation.WaterEnvironment
import com.wakewindow.app.domain.observation.WaterEnvironmentClassifier
import com.wakewindow.app.domain.observation.WaterPointTypeProvider
import com.wakewindow.app.domain.route.BoatingPlan
import com.wakewindow.app.domain.route.BoatingRepository
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
import com.wakewindow.app.domain.scoring.ConfidenceEvidence
import com.wakewindow.app.domain.scoring.EvidenceItem
import com.wakewindow.app.domain.scoring.MarineScoreEngine
import com.wakewindow.app.domain.scoring.ObservationalCautionEvaluator
import com.wakewindow.app.domain.tide.CurrentEventsOutcome
import com.wakewindow.app.domain.tide.CurrentProvider
import com.wakewindow.app.domain.tide.CurrentStationOutcome
import com.wakewindow.app.domain.tide.CurrentTimeline
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
 *
 * **Forecast-vs-observation comparison is always station-local** - see
 * [buildObservationComparison] and docs/MARINE_SCORING.md "Forecast vs. observation": a
 * buoy's observation is compared against the forecast *for the buoy's own coordinates*, never
 * against the launch's forecast, because a 23 NM offshore reading disagreeing with an inshore
 * forecast is a location difference, not a forecast error.
 */
class DefaultBoatingRepository(
    private val generalProviders: List<GeneralWeatherProvider>,
    private val marineForecastProviders: List<MarineForecastProvider>,
    private val alertProvider: MarineAlertProvider,
    private val tideProvider: TideProvider,
    /** Nullable and entirely optional - see docs/DATA_SOURCES.md; the assessment must degrade
     * gracefully with buoy observation disabled or unreachable, never fail because of it. */
    private val observationProvider: MarineObservationProvider? = null,
    /** Nullable and entirely optional - see [WaterPointTypeProvider]'s doc comment. Without
     * one, every location classifies as [WaterEnvironment.UNKNOWN], which never gates a
     * category. */
    private val pointTypeProvider: WaterPointTypeProvider? = null,
    /** Nullable and entirely optional, exactly like [tideProvider] - the assessment must
     * degrade gracefully with no current station in range rather than fail. */
    private val currentProvider: CurrentProvider? = null,
) : BoatingRepository {

    /** A forecast-at-station value is only compared against the observation when the nearest
     * available forecast hour is within this much of the observation's own timestamp - beyond
     * this, "closest hour we had data for" stops being a meaningful stand-in for "forecast for
     * this moment," and the comparison is reported as [ComparisonStatus.TIME_MISALIGNED]
     * rather than silently comparing mismatched times. */
    private val forecastAlignmentWindow: Duration = Duration.ofMinutes(90)

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
        val currentDeferred = async { fetchCurrentConditions(location, start, end, hours) }
        val observationDeferred = async { fetchObservation(location) }
        val launchPointTypeDeferred = async { safePointType(location) }

        val generalOutcomes = generalDeferred.awaitAll()
        val marineOutcomes = marineDeferred.awaitAll()
        val alertsOutcome = alertsDeferred.await()
        val tideResult = tideDeferred.await()
        val currentResult = currentDeferred.await()
        val observationOutcome = observationDeferred.await()
        val launchPointType = launchPointTypeDeferred.await()

        // Environment classification uses signals already gathered above (NWS point type,
        // distance to the nearest tide station) - see docs/STATION_REPRESENTATIVENESS.md. Never
        // an extra network call on its own.
        val launchEnvironment = WaterEnvironmentClassifier.classify(launchPointType, tideResult.station?.distanceNm)

        // A failed alert check is NOT the same fact as "checked, zero alerts are active" - see
        // docs/ASSESSMENT_VALIDATION.md "Missing data policy." Silently treating a failure as
        // a clean bill of health would let an actual Special Marine Warning go completely
        // unrepresented just because the alerts endpoint timed out.
        val alertCheckFailed = alertsOutcome is MarineAlertOutcome.Failure
        val alerts: List<MarineAlert> = (alertsOutcome as? MarineAlertOutcome.Success)?.alerts ?: emptyList()

        val weatherReadings: List<MarineConditions> =
            generalOutcomes.flatMap { it.second.successOrEmpty() } + marineOutcomes.flatMap { it.second.successOrEmpty() }

        val readingsByHour: Map<Instant, List<MarineConditions>> = hours.associateWith { hour ->
            weatherReadings.filter { it.timestamp == hour } +
                tideResult.conditions.filter { it.timestamp == hour } +
                currentResult.conditions.filter { it.timestamp == hour }
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

        // Forecast-vs-observation comparison - always at the STATION's own coordinates, never
        // the launch's (see class doc and docs/MARINE_SCORING.md "Forecast vs. observation").
        // This is evidence about how well the forecast is verifying, not itself a forecast
        // value, so it's never blended into mergedByHour.
        val comparison = buildObservationComparison(observationOutcome, launchEnvironment)
        if (comparison != null && comparison.disagreements.isNotEmpty()) {
            val departureHour = plan.departureTime.truncatedTo(ChronoUnit.HOURS)
            mergedByHour[departureHour] = mergedByHour[departureHour]?.let { forecast ->
                forecast.copy(
                    confidence = forecast.confidence.worstOf(
                        Confidence(ConfidenceLevel.MEDIUM, comparison.disagreements.map { it.message }),
                    ),
                )
            }
        }

        // A fresh, representative, materially-worse observation can only ever gate the
        // departure point via an explicit Hazard - never by averaging into the forecast. See
        // [ObservationalCautionEvaluator] and docs/MARINE_SCORING.md "Observation influence."
        val observationalCaution = ObservationalCautionEvaluator.evaluate(comparison, plan.departureTime, plan.vessel)

        val baseAssessment = MarineScoreEngine.assess(
            samples = samples,
            conditionsFor = { sample -> nearestHourConditions(mergedByHour, sample.estimatedTime) },
            vessel = plan.vessel,
            observationalCaution = observationalCaution,
            environment = launchEnvironment,
        )

        val station = (observationOutcome as? MarineObservationOutcome.Success)?.station
        val evidence = buildEvidence(generalOutcomes, marineOutcomes, tideResult, currentResult, station, alertCheckFailed)

        baseAssessment.copy(
            evidence = evidence,
            nearestObservationStation = station,
            disagreements = comparison?.disagreements ?: emptyList(),
            observationComparison = comparison,
            waterEnvironment = launchEnvironment,
            nearestTideStation = tideResult.station,
            nearestCurrentStation = currentResult.station,
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

    private suspend fun safePointType(location: GeoPoint): String? =
        try {
            pointTypeProvider?.pointType(location)
        } catch (e: Exception) {
            null
        }

    /**
     * Compares the station's observation against a forecast fetched **for the station's own
     * coordinates**, in a narrow window around the observation timestamp - never the launch's
     * forecast, and never the whole trip's hourly series (a handful of hours around the
     * observation is all that's needed). Returns null only when there's no station/observation
     * to compare at all; when a station exists but the comparison can't be completed, that's
     * reported as a [ComparisonStatus] on a real result instead, never silently dropped.
     */
    private suspend fun buildObservationComparison(
        observationOutcome: MarineObservationOutcome,
        launchEnvironment: WaterEnvironment,
    ): ObservationForecastComparison? = coroutineScope {
        val success = observationOutcome as? MarineObservationOutcome.Success ?: return@coroutineScope null
        val station = success.station
        val observedAt = success.conditions.timestamp
        val windowStart = observedAt.minus(2, ChronoUnit.HOURS)
        val windowEnd = observedAt.plus(2, ChronoUnit.HOURS)
        val stationHours = hourlySequence(windowStart, windowEnd)

        val stationPointTypeDeferred = async { safePointType(station.location) }
        val stationTideDeferred = async { safeTideStationCall { tideProvider.nearestStation(station.location) } }
        val stationGeneralDeferred = generalProviders.map { provider ->
            async { safeCall { provider.hourlyForecast(station.location, windowStart, windowEnd) } }
        }
        val stationMarineDeferred = marineForecastProviders.map { provider ->
            async { safeCall { provider.hourlyMarineForecast(station.location, windowStart, windowEnd) } }
        }

        val stationPointType = stationPointTypeDeferred.await()
        val stationTideOutcome = stationTideDeferred.await()
        val stationReadings = (stationGeneralDeferred.awaitAll() + stationMarineDeferred.awaitAll()).flatMap { it.successOrEmpty() }

        val stationTideDistanceNm = (stationTideOutcome as? TideStationOutcome.Found)?.station?.distanceNm
        val stationEnvironment = WaterEnvironmentClassifier.classify(stationPointType, stationTideDistanceNm)

        val representativeness = StationRepresentativenessEvaluator.evaluate(
            distanceNm = station.distanceNm,
            launchEnvironment = launchEnvironment,
            stationEnvironment = stationEnvironment,
            freshness = station.freshness,
        )

        val stationByHour: Map<Instant, MarineConditions?> = stationHours.associateWith { hour ->
            MarineConsensus.merge(stationReadings.filter { it.timestamp == hour })
        }
        val forecastAtStation = nearestHourConditions(stationByHour, observedAt)
        val alignment = forecastAtStation?.let { Duration.between(it.timestamp, observedAt).abs() }

        val status = when {
            forecastAtStation == null -> ComparisonStatus.NO_FORECAST_AT_STATION
            alignment != null && alignment > forecastAlignmentWindow -> ComparisonStatus.TIME_MISALIGNED
            else -> ComparisonStatus.COMPARABLE
        }

        // An unusably-stale reading isn't meaningful evidence of a CURRENT disagreement - the
        // comparison itself (and its representativeness, which already explains why) is still
        // reported, but nothing is surfaced as if it described conditions right now. See
        // docs/MARINE_SCORING.md "Forecast vs. observation."
        val disagreements = if (status == ComparisonStatus.COMPARABLE && forecastAtStation != null && station.freshness != ObservationFreshness.UNUSABLE) {
            MarineDisagreementDetector.detect(forecastAtStation, success.conditions)
        } else {
            emptyList()
        }

        ObservationForecastComparison(
            station = station,
            representativeness = representativeness,
            observedAt = observedAt,
            forecastValidAt = forecastAtStation?.timestamp,
            disagreements = disagreements,
            status = status,
        )
    }

    private fun buildEvidence(
        generalOutcomes: List<Pair<String, ForecastOutcome>>,
        marineOutcomes: List<Pair<String, ForecastOutcome>>,
        tideResult: TideFetchResult,
        currentResult: CurrentFetchResult,
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
        val hasCurrentStation = currentResult.station != null
        val hasFreshBuoy = station != null && station.freshness != ObservationFreshness.UNUSABLE

        val items = listOf(
            EvidenceItem("NWS/general weather forecast", hasGeneralForecast),
            EvidenceItem("Marine (wind/wave) forecast", hasMarineForecast),
            EvidenceItem("NOAA tide station", hasTideStation),
            EvidenceItem("NOAA current station", hasCurrentStation),
            EvidenceItem("Nearby buoy observation", hasFreshBuoy),
        )

        val limitations = buildList {
            if (!hasMarineForecast) add("No marine wave/swell forecast available for this location")
            if (!hasTideStation) add("No tide station within range - this water may be non-tidal or outside NOAA CO-OPS coverage")
            if (!hasCurrentStation) add("No current station within range for this location")
            if (station == null) add("No nearby buoy observation available")
            else if (station.freshness == ObservationFreshness.STALE) add("Nearest buoy observation is ${station.ageMinutes} min old - treated as historical context, not current")
            else if (station.freshness == ObservationFreshness.UNUSABLE) add("Nearest buoy observation is too old (${station.ageMinutes} min) to use")
            else if (station.distanceNm > 25.0) add("Nearest buoy observation is ${String.format("%.0f", station.distanceNm)} NM away")
            if (alertCheckFailed) add("Marine alert status could not be verified this refresh")
        }

        return ConfidenceEvidence(items, limitations)
    }

    private data class TideFetchResult(val conditions: List<MarineConditions>, val station: com.wakewindow.app.domain.tide.TideStation?)
    private data class CurrentFetchResult(val conditions: List<MarineConditions>, val station: com.wakewindow.app.domain.tide.CurrentStation?)

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

    private suspend fun fetchCurrentConditions(location: GeoPoint, start: Instant, end: Instant, hours: List<Instant>): CurrentFetchResult {
        val provider = currentProvider ?: return CurrentFetchResult(emptyList(), null)
        val stationOutcome = safeCurrentStationCall { provider.nearestStation(location) }
        val station = (stationOutcome as? CurrentStationOutcome.Found)?.station ?: return CurrentFetchResult(emptyList(), null)

        val dates = datesBetween(start, end)
        val events = dates.flatMap { date ->
            when (val outcome = safeCurrentEventsCall { provider.events(station.stationId, date) }) {
                is CurrentEventsOutcome.Success -> outcome.events
                else -> emptyList()
            }
        }
        if (events.isEmpty()) return CurrentFetchResult(emptyList(), station)

        val source = SourceReference(
            sourceName = "NOAA Tides & Currents",
            sourceUrl = "https://tidesandcurrents.noaa.gov/stationhome.html?id=${station.stationId}",
            retrievedAt = Instant.now(),
            stationId = station.stationId,
            stationName = station.name,
            stationDistanceNm = station.distanceNm,
        )
        return CurrentFetchResult(CurrentTimeline.conditionsAt(events, hours, location, source), station)
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

    private suspend fun safeCurrentStationCall(block: suspend () -> CurrentStationOutcome): CurrentStationOutcome =
        try {
            block()
        } catch (e: Exception) {
            CurrentStationOutcome.Failure(e.message ?: "Unknown current provider error", e)
        }

    private suspend fun safeCurrentEventsCall(block: suspend () -> CurrentEventsOutcome): CurrentEventsOutcome =
        try {
            block()
        } catch (e: Exception) {
            CurrentEventsOutcome.Failure(e.message ?: "Unknown current events error", e)
        }

    private fun ForecastOutcome.successOrEmpty(): List<MarineConditions> =
        (this as? ForecastOutcome.Success)?.hourly ?: emptyList()
}
