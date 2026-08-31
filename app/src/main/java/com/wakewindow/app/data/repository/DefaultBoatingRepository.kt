package com.wakewindow.app.data.repository

import com.wakewindow.app.domain.alert.MarineAlert
import com.wakewindow.app.domain.alert.MarineAlertOutcome
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.consensus.MarineConsensus
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.route.BoatingPlan
import com.wakewindow.app.domain.route.BoatingRepository
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
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
 * [MarineConsensus], attaches active marine alerts and tide data, then hands the whole
 * per-hour timeline to [MarineScoreEngine]. One provider failing never blocks the others -
 * see docs/RIDECAST_REFERENCE_AUDIT.md section 1/11 for the pattern this follows.
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
) : BoatingRepository {

    override suspend fun buildAssessment(plan: BoatingPlan): BoatingWindowAssessment = coroutineScope {
        val samples = plan.defaultRouteSamples()
        val location = plan.launch.location
        val start = plan.departureTime.minus(1, ChronoUnit.HOURS)
        val end = plan.returnTime.plus(1, ChronoUnit.HOURS)
        val hours = hourlySequence(start, end)

        val generalDeferred = generalProviders.map { provider ->
            async { safeCall { provider.hourlyForecast(location, start, end) } }
        }
        val marineDeferred = marineForecastProviders.map { provider ->
            async { safeCall { provider.hourlyMarineForecast(location, start, end) } }
        }
        val alertsDeferred = async { safeAlertCall { alertProvider.activeAlerts(location) } }
        val tideDeferred = async { fetchTideConditions(location, start, end, hours) }

        val generalOutcomes = generalDeferred.awaitAll()
        val marineOutcomes = marineDeferred.awaitAll()
        val alertsOutcome = alertsDeferred.await()
        val tideConditions = tideDeferred.await()

        val alerts: List<MarineAlert> = (alertsOutcome as? MarineAlertOutcome.Success)?.alerts ?: emptyList()

        val weatherReadings: List<MarineConditions> =
            generalOutcomes.flatMap { it.successOrEmpty() } + marineOutcomes.flatMap { it.successOrEmpty() }

        val readingsByHour: Map<Instant, List<MarineConditions>> = hours.associateWith { hour ->
            weatherReadings.filter { it.timestamp == hour } + tideConditions.filter { it.timestamp == hour }
        }

        val mergedByHour: Map<Instant, MarineConditions?> = readingsByHour.mapValues { (hour, readings) ->
            val merged = MarineConsensus.merge(readings) ?: return@mapValues null
            val activeAlerts = alerts.filter { it.isActiveAt(hour) }
            if (activeAlerts.isEmpty()) merged else merged.copy(marineAlerts = activeAlerts)
        }

        MarineScoreEngine.assess(
            samples = samples,
            conditionsFor = { sample -> nearestHourConditions(mergedByHour, sample.estimatedTime) },
            vessel = plan.vessel,
        )
    }

    private suspend fun fetchTideConditions(location: GeoPoint, start: Instant, end: Instant, hours: List<Instant>): List<MarineConditions> {
        val stationOutcome = safeTideStationCall { tideProvider.nearestStation(location) }
        val station = (stationOutcome as? TideStationOutcome.Found)?.station ?: return emptyList()

        val dates = datesBetween(start, end)
        val events: List<TideEvent> = dates.flatMap { date ->
            when (val outcome = safeTideEventsCall { tideProvider.events(station.stationId, date) }) {
                is TideEventsOutcome.Success -> outcome.events
                else -> emptyList()
            }
        }
        if (events.isEmpty()) return emptyList()

        val source = SourceReference(
            sourceName = "NOAA Tides & Currents",
            sourceUrl = "https://tidesandcurrents.noaa.gov/stationhome.html?id=${station.stationId}",
            retrievedAt = Instant.now(),
            stationId = station.stationId,
            stationName = station.name,
            stationDistanceNm = station.distanceNm,
        )
        return TideTimeline.conditionsAt(events, hours, location, source)
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
