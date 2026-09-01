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
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.route.RouteSampleRole
import com.wakewindow.app.domain.scoring.Hazard
import com.wakewindow.app.domain.scoring.MarinePointScorer
import com.wakewindow.app.domain.scoring.ObservationalCautionEvaluator
import com.wakewindow.app.domain.scoring.PointAssessment
import com.wakewindow.app.domain.tide.CurrentEventsOutcome
import com.wakewindow.app.domain.tide.CurrentProvider
import com.wakewindow.app.domain.tide.CurrentStation
import com.wakewindow.app.domain.tide.CurrentStationOutcome
import com.wakewindow.app.domain.tide.CurrentTimeline
import com.wakewindow.app.domain.tide.TideEvent
import com.wakewindow.app.domain.tide.TideEventsOutcome
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.tide.TideStation
import com.wakewindow.app.domain.tide.TideStationOutcome
import com.wakewindow.app.domain.tide.TideTimeline
import com.wakewindow.app.domain.trip.MarineTripPlan
import com.wakewindow.app.domain.trip.TripAssessment
import com.wakewindow.app.domain.trip.TripAssessmentBuilder
import com.wakewindow.app.domain.trip.TripBoatingRepository
import com.wakewindow.app.domain.trip.TripLeg
import com.wakewindow.app.domain.trip.TripLegAssessment
import com.wakewindow.app.domain.trip.TripLegEstimator
import com.wakewindow.app.domain.trip.TripPlanLimits
import com.wakewindow.app.domain.trip.TripPointAssessment
import com.wakewindow.app.domain.trip.TripPointKind
import com.wakewindow.app.domain.trip.WeatherSampleGenerator
import com.wakewindow.app.domain.vessel.VesselProfile
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
 * The Mode B ([MarineTripPlan]) counterpart to [DefaultBoatingRepository] - see
 * docs/TRIP_ASSESSMENT.md. Where [DefaultBoatingRepository] fetches one location's hourly
 * timeline once, this fetches conditions **per trip point, at that point's own expected arrival
 * time** (departure, every generated weather sample, every user waypoint, the destination) -
 * see docs/ROADMAP.md Sprint 5 "Timed waypoint weather." Every point's fetch degrades
 * independently (see [fetchPointSafely]): one waypoint's provider failure never blocks another's
 * assessment, matching [DefaultBoatingRepository]'s own resilience contract.
 *
 * Deliberately a separate class rather than a refactor of [DefaultBoatingRepository]'s internals
 * - the two share the same provider set and the same per-location fetch *shape*, but Mode A's
 * implementation is already live-validated (Sprint 4.5) and the sprint brief is explicit: "Do
 * not revisit Sprint 4 unless a real regression is found." Some duplication of small, stable
 * helpers (safe-call wrappers, hour/date sequencing) is the accepted cost of not touching that
 * verified path.
 */
class DefaultTripBoatingRepository(
    private val generalProviders: List<GeneralWeatherProvider>,
    private val marineForecastProviders: List<MarineForecastProvider>,
    private val alertProvider: MarineAlertProvider,
    private val tideProvider: TideProvider,
    private val observationProvider: MarineObservationProvider? = null,
    private val pointTypeProvider: WaterPointTypeProvider? = null,
    private val currentProvider: CurrentProvider? = null,
    private val clock: () -> Instant = Instant::now,
) : TripBoatingRepository {

    /** An observation more than this far from a point's own expected arrival says nothing
     * useful about conditions the boater will actually experience there - the same window
     * [ObservationalCautionEvaluator] applies to Mode A's single departure point, applied here
     * per trip point instead. See docs/TRIP_ASSESSMENT.md "Observation relevance." */
    private val nearTermObservationWindow: Duration = Duration.ofHours(3)

    private val forecastAlignmentWindow: Duration = Duration.ofMinutes(90)

    private data class Entry(val kind: TripPointKind, val name: String?, val location: GeoPoint, val at: Instant)

    override suspend fun buildTripAssessment(plan: MarineTripPlan): TripAssessment = coroutineScope {
        val now = clock()
        val limitViolations = TripPlanLimits.validate(plan)
        val legs = TripLegEstimator.estimateLegs(plan)

        val allEntries = mutableListOf(Entry(TripPointKind.DEPARTURE, plan.departure.name, plan.departure.location, plan.departureTime))
        val legEntryGroups = mutableListOf<Pair<TripLeg, List<Entry>>>()
        var legStart = plan.departureTime
        legs.forEachIndexed { index, leg ->
            val sampleEntries = WeatherSampleGenerator.samplesFor(leg, legStart)
                .map { s -> Entry(TripPointKind.WEATHER_SAMPLE, null, s.location, s.estimatedTime) }
            val arrivalKind = if (index == legs.lastIndex) TripPointKind.DESTINATION else TripPointKind.WAYPOINT
            val arrivalEntry = Entry(arrivalKind, leg.to.name, leg.to.location, leg.estimatedArrival)
            val group = sampleEntries + arrivalEntry
            legEntryGroups += leg to group
            allEntries += group
            legStart = leg.estimatedArrival
        }

        val results = allEntries.map { entry -> async { entry to fetchPointSafely(entry, plan.vessel, now) } }.awaitAll()
        val timeline = results.map { (entry, result) -> buildTripPointAssessment(entry, result) }

        var previousArrival = timeline.first()
        var cursor = 1
        val legAssessments = legEntryGroups.map { (leg, group) ->
            val slice = timeline.subList(cursor, cursor + group.size)
            cursor += group.size
            val to = slice.last()
            val samples = slice.dropLast(1)
            val assessment = TripLegAssessment(leg = leg, from = previousArrival, to = to, weatherSamples = samples)
            previousArrival = to
            assessment
        }

        val horizonWarning = if (results.any { it.second.beyondHorizon }) {
            "Marine forecast is not available this far ahead yet for one or more points in this plan - their conditions are shown as unavailable rather than guessed."
        } else {
            null
        }

        TripAssessmentBuilder.build(plan, timeline, legAssessments, horizonWarning, limitViolations)
    }

    private fun buildTripPointAssessment(entry: Entry, result: PointFetchResult): TripPointAssessment {
        val sample = RouteSample(
            location = entry.location,
            role = when (entry.kind) {
                TripPointKind.DEPARTURE -> RouteSampleRole.DEPARTURE
                TripPointKind.WEATHER_SAMPLE -> RouteSampleRole.WEATHER_SAMPLE
                TripPointKind.WAYPOINT -> RouteSampleRole.WAYPOINT
                TripPointKind.DESTINATION -> RouteSampleRole.DESTINATION
            },
            progressFraction = 0.0,
            estimatedTime = entry.at,
        )
        // result.point was already scored against the plan's real vessel inside fetchPoint()/
        // fetchPointSafely() - MarinePointScorer never reads sample.role for scoring, only
        // stores it for display, so it's safe to swap in the entry's real role/location here
        // without rescoring.
        var point = result.point.copy(sample = sample)

        val hazards = if (result.observationalCaution != null) {
            point.hazards + result.observationalCaution
        } else {
            point.hazards
        }
        val category = result.observationalCaution?.categoryCap
            ?.let { cap -> com.wakewindow.app.domain.scoring.worstCategory(point.category, cap) }
            ?: point.category
        point = point.copy(category = category, hazards = hazards)

        if (result.beyondHorizon) {
            point = point.copy(
                confidence = Confidence(
                    ConfidenceLevel.UNAVAILABLE,
                    listOf("Marine forecast is not available this far ahead yet"),
                ),
            )
        }

        return TripPointAssessment(
            kind = entry.kind,
            name = entry.name,
            point = point,
            waterEnvironment = result.environment,
            nearestTideStation = result.tideStation,
            nearestCurrentStation = result.currentStation,
            nearestObservationStation = result.observationStation,
            observationApplicable = result.observationApplicable,
        )
    }

    private data class PointFetchResult(
        val conditions: MarineConditions?,
        val point: PointAssessment,
        val environment: WaterEnvironment,
        val tideStation: TideStation?,
        val currentStation: CurrentStation?,
        val observationStation: SelectedMarineStation?,
        val observationApplicable: Boolean,
        val observationalCaution: Hazard?,
        val beyondHorizon: Boolean,
    )

    /**
     * Fetches and scores everything for one trip point. Wrapped as a single unit so that any
     * unexpected failure anywhere in this point's own fetch degrades to an honest UNAVAILABLE
     * point rather than failing [buildTripAssessment] outright - see docs/TRIP_ASSESSMENT.md
     * "Error resilience": one waypoint's provider failure must never block another's.
     */
    private suspend fun fetchPointSafely(entry: Entry, vessel: VesselProfile, now: Instant): PointFetchResult {
        return try {
            fetchPoint(entry, vessel, now)
        } catch (e: Exception) {
            val sample = RouteSample(entry.location, RouteSampleRole.UNDERWAY, 0.0, entry.at)
            PointFetchResult(
                conditions = null,
                point = PointAssessment(
                    at = entry.at,
                    sample = sample,
                    conditions = null,
                    category = com.wakewindow.app.domain.scoring.BoatingCategory.UNAVAILABLE,
                    score = 0,
                    hazards = emptyList(),
                    confidence = Confidence.unavailable(
                        "Conditions unavailable near ${entry.name ?: "a weather sample point"}: ${e.message ?: "unknown error"}",
                    ),
                ),
                environment = WaterEnvironment.UNKNOWN,
                tideStation = null,
                currentStation = null,
                observationStation = null,
                observationApplicable = false,
                observationalCaution = null,
                beyondHorizon = false,
            )
        }
    }

    private suspend fun fetchPoint(entry: Entry, vessel: VesselProfile, now: Instant): PointFetchResult = coroutineScope {
        val location = entry.location
        val at = entry.at

        if (Duration.between(now, at).abs() > TripPlanLimits.MAX_FORECAST_HORIZON) {
            val sample = RouteSample(location, RouteSampleRole.UNDERWAY, 0.0, at)
            return@coroutineScope PointFetchResult(
                conditions = null,
                point = MarinePointScorer.score(sample, null, vessel, WaterEnvironment.UNKNOWN),
                environment = WaterEnvironment.UNKNOWN,
                tideStation = null,
                currentStation = null,
                observationStation = null,
                observationApplicable = false,
                observationalCaution = null,
                beyondHorizon = true,
            )
        }

        val start = at.minus(1, ChronoUnit.HOURS)
        val end = at.plus(1, ChronoUnit.HOURS)
        val hours = hourlySequence(start, end)

        val generalDeferred = generalProviders.map { provider -> async { safeCall { provider.hourlyForecast(location, start, end) } } }
        val marineDeferred = marineForecastProviders.map { provider -> async { safeCall { provider.hourlyMarineForecast(location, start, end) } } }
        val alertsDeferred = async { safeAlertCall { alertProvider.activeAlerts(location) } }
        val tideDeferred = async { fetchTideConditions(location, start, end, hours) }
        val currentDeferred = async { fetchCurrentConditions(location, start, end, hours) }
        val pointTypeDeferred = async { safePointType(location) }
        val nearTerm = Duration.between(now, at).abs() <= nearTermObservationWindow
        val observationDeferred = async { if (nearTerm) fetchObservation(location) else MarineObservationOutcome.NoStationAvailable }

        val generalOutcomes = generalDeferred.awaitAll()
        val marineOutcomes = marineDeferred.awaitAll()
        val alertsOutcome = alertsDeferred.await()
        val tideResult = tideDeferred.await()
        val currentResult = currentDeferred.await()
        val pointType = pointTypeDeferred.await()
        val observationOutcome = observationDeferred.await()

        val environment = WaterEnvironmentClassifier.classify(pointType, tideResult.station?.distanceNm)

        val alertCheckFailed = alertsOutcome is MarineAlertOutcome.Failure
        val alerts: List<MarineAlert> = (alertsOutcome as? MarineAlertOutcome.Success)?.alerts ?: emptyList()

        val weatherReadings: List<MarineConditions> =
            generalOutcomes.flatMap { it.successOrEmpty() } + marineOutcomes.flatMap { it.successOrEmpty() }

        val readingsByHour: Map<Instant, List<MarineConditions>> = hours.associateWith { hour ->
            weatherReadings.filter { it.timestamp == hour } +
                tideResult.conditions.filter { it.timestamp == hour } +
                currentResult.conditions.filter { it.timestamp == hour }
        }

        val mergedByHour: Map<Instant, MarineConditions?> = readingsByHour.mapValues { (hour, readings) ->
            var merged = MarineConsensus.merge(readings) ?: return@mapValues null
            val activeAlerts = alerts.filter { it.isActiveAt(hour) }
            if (activeAlerts.isNotEmpty()) merged = merged.copy(marineAlerts = activeAlerts)
            if (alertCheckFailed) {
                merged = merged.copy(
                    confidence = merged.confidence.worstOf(
                        Confidence(ConfidenceLevel.MEDIUM, listOf("Marine alert status could not be verified for this point")),
                    ),
                )
            }
            merged
        }

        val conditions = nearestHourConditions(mergedByHour, at)

        val observationStation = (observationOutcome as? MarineObservationOutcome.Success)?.station
        val comparison = if (nearTerm) buildObservationComparison(observationOutcome, environment) else null
        val observationalCaution = ObservationalCautionEvaluator.evaluate(comparison, at, vessel)

        val point = MarinePointScorer.score(RouteSample(location, RouteSampleRole.UNDERWAY, 0.0, at), conditions, vessel, environment)

        PointFetchResult(
            conditions = conditions,
            point = point,
            environment = environment,
            tideStation = tideResult.station,
            currentStation = currentResult.station,
            observationStation = observationStation,
            observationApplicable = nearTerm,
            observationalCaution = observationalCaution,
            beyondHorizon = false,
        )
    }

    private suspend fun buildObservationComparison(
        observationOutcome: MarineObservationOutcome,
        pointEnvironment: WaterEnvironment,
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
            launchEnvironment = pointEnvironment,
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

    private data class TideFetchResult(val conditions: List<MarineConditions>, val station: TideStation?)
    private data class CurrentFetchResult(val conditions: List<MarineConditions>, val station: CurrentStation?)

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
