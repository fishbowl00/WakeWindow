package com.wakewindow.app.ui.assessment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.observation.ComparisonStatus
import com.wakewindow.app.domain.observation.MarineDisagreement
import com.wakewindow.app.domain.observation.ObservationForecastComparison
import com.wakewindow.app.domain.observation.ObservationFreshness
import com.wakewindow.app.domain.observation.RepresentativenessLevel
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.scoring.BestWindow
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
import com.wakewindow.app.domain.scoring.EvidenceItem
import com.wakewindow.app.domain.scoring.Hazard
import com.wakewindow.app.domain.scoring.PointAssessment
import com.wakewindow.app.domain.scoring.severityRank
import com.wakewindow.app.domain.tide.CurrentEventType
import com.wakewindow.app.domain.tide.TideTrend
import com.wakewindow.app.ui.WakeWindowUiState
import com.wakewindow.app.ui.theme.label
import com.wakewindow.app.ui.theme.toColor
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentScreen(
    state: WakeWindowUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(state.activeLaunch?.id, state.departureTime, state.returnTime) {
        if (state.assessment == null && !state.isLoadingAssessment) onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.activeLaunch?.place?.name ?: "Conditions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh conditions")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoadingAssessment -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.assessmentError != null -> Text(
                    "Couldn't load conditions: ${state.assessmentError}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.assessment != null -> AssessmentContent(state.assessment, state.zoneId)
                else -> Unit
            }
        }
    }
}

@Composable
private fun AssessmentContent(assessment: BoatingWindowAssessment, zoneId: ZoneId) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { OverallCard(assessment) }
        assessment.bestWindow?.let { window -> item { BestWindowCard(window, zoneId) } }
        item { ConditionsGrid(assessment.departureAssessment) }
        if (assessment.departureAssessment.conditions?.tideHeightFt != null || assessment.departureAssessment.conditions?.currentSpeedKts != null) {
            item { TideCurrentCard(assessment, zoneId) }
        }
        item { WindowBreakdownCard(assessment) }
        if (assessment.worstHazards.isNotEmpty()) {
            item { HazardsCard(assessment.worstHazards, zoneId) }
        }
        if (assessment.disagreements.isNotEmpty()) {
            item { DisagreementsCard(assessment.disagreements) }
        }
        assessment.nearestObservationStation?.let { station -> item { ObservationStationCard(station, assessment.observationComparison) } }
        item { ConfidenceCard(assessment) }
        item { SafetyFooter() }
    }
}

@Composable
private fun OverallCard(assessment: BoatingWindowAssessment) {
    val overall = assessment.overallAssessment
    val color = overall.category.toColor()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${overall.score}",
                    color = MaterialTheme.colorScheme.background,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    overall.category.label().uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
                if (overall.reasons.isNotEmpty()) {
                    Text(
                        overall.reasons.first().message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * "Best Window" only appears as a distinct recommendation when it's genuinely different from
 * what the user already planned - see docs/MARINE_SCORING.md "Best Window." When the planned
 * window already is the best window, this says so plainly instead of implying a better option
 * exists.
 */
@Composable
private fun BestWindowCard(window: BestWindow, zoneId: ZoneId) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (window.matchesPlannedWindow) {
                Text("Your planned window is excellent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${formatTime(window.start, zoneId)} - ${formatTime(window.end, zoneId)} stays GOOD or better the whole time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Best window", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${formatTime(window.start, zoneId)} - ${formatTime(window.end, zoneId)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                window.recommendReturnBy?.let {
                    Text(
                        "Return by ${formatTime(it, zoneId)} recommended",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (window.reasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Why", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                window.reasons.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ConditionsGrid(point: PointAssessment) {
    val c = point.conditions
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("At departure", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricColumn("Wind", c?.sustainedWindKts?.let { "${it.roundToInt()} kt" } ?: "—", c?.gustKts?.let { "Gusts ${it.roundToInt()} kt" })
                MetricColumn("Seas", c?.waveHeightFt?.let { "${String.format("%.1f", it)} ft" } ?: "—", c?.wavePeriodSec?.let { "${it.roundToInt()}s period" })
                MetricColumn(
                    "Tide",
                    c?.tideHeightFt?.let { "${String.format("%.1f", it)} ft" } ?: "Not tidal",
                    c?.tideTrend?.label(),
                )
                MetricColumn("Storm", c?.thunderstormProbabilityPercent?.let { "$it%" } ?: "—", null)
            }
        }
    }
}

/**
 * A whole-outing tide/current timeline (departure -> next turn -> return) rather than a single
 * point-in-time reading - see docs/PLANNING.md "Currents/tides." Everything here is explicitly
 * a *prediction*, never presented as a live observation (see docs/DATA_SOURCES.md "Current
 * predictions") - CO-OPS publishes harmonic predictions for the overwhelming majority of
 * stations, not a continuous sensor feed. A tide height is a prediction about the water
 * surface, never a claim about depth/clearance under a hull.
 */
@Composable
private fun TideCurrentCard(assessment: BoatingWindowAssessment, zoneId: ZoneId) {
    val departure = assessment.departureAssessment.conditions
    val returnConditions = assessment.returnAssessment.conditions
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (departure?.tideHeightFt != null) {
                Text("Tide (prediction)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                TimelineRow("Departure", "${String.format("%.1f", departure.tideHeightFt)} ft · ${departure.tideTrend?.label() ?: "Unknown"}")
                val nextTideEvents = listOfNotNull(
                    departure.nextHighTide?.let { "Next high" to it },
                    departure.nextLowTide?.let { "Next low" to it },
                )
                nextTideEvents.minByOrNull { it.second.time }?.let { (label, next) ->
                    TimelineRow(label, "${String.format("%.1f", next.heightFt)} ft at ${formatTime(next.time, zoneId)}")
                }
                returnConditions?.tideHeightFt?.let {
                    TimelineRow("Return", "${String.format("%.1f", it)} ft · ${returnConditions.tideTrend?.label() ?: "Unknown"}")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (departure?.currentSpeedKts != null) {
                Text("Current (prediction)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                TimelineRow("Departure", currentSummary(departure.currentSpeedKts, departure.currentDirectionDeg))
                departure.nextCurrentEvent?.let { next ->
                    TimelineRow(next.type.label(), "${formatTime(next.time, zoneId)}${if (next.type != CurrentEventType.SLACK) " · ${String.format("%.1f", next.speedKts)} kt" else ""}")
                }
                returnConditions?.currentSpeedKts?.let {
                    TimelineRow("Return", currentSummary(it, returnConditions.currentDirectionDeg))
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun currentSummary(speedKts: Double, directionDeg: Double?): String {
    val speed = "${String.format("%.1f", speedKts)} kt"
    return if (directionDeg != null) "$speed @ ${directionDeg.roundToInt()}°" else speed
}

private fun CurrentEventType.label(): String = when (this) {
    CurrentEventType.FLOOD_MAX -> "Max flood"
    CurrentEventType.EBB_MAX -> "Max ebb"
    CurrentEventType.SLACK -> "Slack"
}

private fun TideTrend.label(): String = when (this) {
    TideTrend.RISING -> "Rising"
    TideTrend.FALLING -> "Falling"
    TideTrend.NEAR_HIGH -> "Near high"
    TideTrend.NEAR_LOW -> "Near low"
    TideTrend.UNKNOWN -> "Unknown"
}

@Composable
private fun MetricColumn(label: String, value: String, sub: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WindowBreakdownCard(assessment: BoatingWindowAssessment) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Your outing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            PointRow("Departure", assessment.departureAssessment)
            if (assessment.underwayAssessments.isNotEmpty()) {
                val worst = assessment.underwayAssessments.maxByOrNull { it.category.severityRank }
                worst?.let { PointRow("Underway (worst hour)", it) }
            }
            PointRow("Return", assessment.returnAssessment)
        }
    }
}

@Composable
private fun PointRow(label: String, point: PointAssessment) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Surface(color = point.category.toColor(), shape = MaterialTheme.shapes.small) {
            Text(
                point.category.label(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}

@Composable
private fun HazardsCard(hazards: List<Hazard>, zoneId: ZoneId) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Main concerns", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            hazards.take(4).forEach { hazard ->
                Text(
                    "• ${hazard.message} (${formatTime(hazard.at, zoneId)})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DisagreementsCard(disagreements: List<MarineDisagreement>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Forecast vs. observed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            disagreements.forEach {
                Text("• ${it.message}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

/**
 * Shows not just what the station observed, but how much that observation should be trusted
 * as evidence for *this* launch - see docs/STATION_REPRESENTATIVENESS.md. A fresh, nearby
 * reading and a fresh, 40 NM offshore reading are not the same kind of evidence, and this card
 * is where that distinction becomes visible rather than implicit.
 */
@Composable
private fun ObservationStationCard(station: SelectedMarineStation, comparison: ObservationForecastComparison?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Nearby observation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(station.name ?: station.stationId, style = MaterialTheme.typography.titleMedium)
            Text(
                "${String.format("%.0f", station.distanceNm)} NM away · observed ${station.ageMinutes} min ago (${station.freshness.label()})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            comparison?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it.representativeness.level.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = it.representativeness.level.toColor(),
                    fontWeight = FontWeight.SemiBold,
                )
                it.representativeness.reasons.forEach { reason ->
                    Text("• $reason", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
                it.status.label()?.let { statusMessage ->
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private fun ObservationFreshness.label(): String = when (this) {
    ObservationFreshness.FRESH -> "fresh"
    ObservationFreshness.AGING -> "aging"
    ObservationFreshness.STALE -> "stale"
    ObservationFreshness.UNUSABLE -> "too old to use"
}

private fun RepresentativenessLevel.label(): String = when (this) {
    RepresentativenessLevel.HIGH -> "Strong evidence for this launch"
    RepresentativenessLevel.MEDIUM -> "Moderate evidence for this launch"
    RepresentativenessLevel.LOW -> "Weak evidence for this launch"
    RepresentativenessLevel.UNKNOWN -> "Evidence strength unknown"
}

private fun RepresentativenessLevel.toColor(): Color = when (this) {
    RepresentativenessLevel.HIGH -> com.wakewindow.app.ui.theme.CategoryColors.Good
    RepresentativenessLevel.MEDIUM -> com.wakewindow.app.ui.theme.CategoryColors.Caution
    RepresentativenessLevel.LOW -> com.wakewindow.app.ui.theme.CategoryColors.Poor
    RepresentativenessLevel.UNKNOWN -> Color.Unspecified
}

/** Only [ComparisonStatus.NO_FORECAST_AT_STATION] and [ComparisonStatus.TIME_MISALIGNED] need
 * an explanation - [ComparisonStatus.COMPARABLE] speaks for itself via any disagreements shown
 * elsewhere, and [ComparisonStatus.NOT_ATTEMPTED] never reaches this card (no station at all). */
private fun ComparisonStatus.label(): String? = when (this) {
    ComparisonStatus.NO_FORECAST_AT_STATION -> "No forecast could be resolved for the station's own location - comparison not possible"
    ComparisonStatus.TIME_MISALIGNED -> "No forecast hour close enough to the observation time to compare"
    ComparisonStatus.COMPARABLE, ComparisonStatus.NOT_ATTEMPTED -> null
}

@Composable
private fun ConfidenceCard(assessment: BoatingWindowAssessment) {
    val confidence = assessment.confidence
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${confidence.level.displayName().uppercase(Locale.getDefault())} CONFIDENCE",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (assessment.evidence.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Based on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                assessment.evidence.items.forEach { EvidenceRow(it) }
            }
            val limitations = assessment.evidence.limitations + confidence.reasons
            if (limitations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Limitations", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                limitations.distinct().forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun EvidenceRow(item: EvidenceItem) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        val icon = if (item.available) Icons.Filled.Check else Icons.Filled.Close
        val tint: Color = if (item.available) com.wakewindow.app.ui.theme.CategoryColors.Good else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(item.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
    }
}

private fun ConfidenceLevel.displayName() = when (this) {
    ConfidenceLevel.HIGH -> "High"
    ConfidenceLevel.MEDIUM -> "Medium"
    ConfidenceLevel.LOW -> "Low"
    ConfidenceLevel.UNAVAILABLE -> "Unavailable"
}

@Composable
private fun SafetyFooter() {
    Text(
        "WakeWindow is a planning aid and does not replace official marine forecasts, nautical charts, Notices to Mariners, local authorities, or responsible seamanship. Conditions can change rapidly.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

private fun formatTime(instant: Instant, zoneId: ZoneId): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    return ZonedDateTime.ofInstant(instant, zoneId).format(formatter)
}
