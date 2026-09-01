package com.wakewindow.app.ui.tripresult

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.trip.TripAssessment
import com.wakewindow.app.domain.trip.TripPointAssessment
import com.wakewindow.app.domain.trip.TripPointKind
import com.wakewindow.app.ui.WakeWindowUiState
import com.wakewindow.app.ui.theme.label
import com.wakewindow.app.ui.theme.toColor
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Trip result screen - see docs/TRIP_ASSESSMENT.md and docs/TRIP_PLANNING.md's own UI sketch
 * ("TRIP ASSESSMENT / CAUTION / Port Canaveral -> Sebastian Inlet / ... / timeline of cards").
 * Deliberately keeps the same overall/hazard/confidence card vocabulary as
 * [com.wakewindow.app.ui.assessment.AssessmentScreen] (reusing [com.wakewindow.app.domain.scoring.BoatingCategory]'s
 * own colors/labels throughout) but replaces its three-point departure/underway/return
 * breakdown with a full chronological timeline, since a trip's whole point is per-point
 * distinctness - see [TripAssessment.timeline].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripResultScreen(
    state: WakeWindowUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(state.tripDraft.departure, state.tripDraft.destination, state.tripDraft.departureTime, state.tripDraft.waypoints) {
        if (state.tripAssessment == null && !state.isLoadingTripAssessment) onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.tripDraft.name.ifBlank { "Trip conditions" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh trip conditions")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoadingTripAssessment -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.tripAssessmentError != null -> Text(
                    "Couldn't load trip conditions: ${state.tripAssessmentError}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.tripAssessment != null -> TripResultContent(state.tripAssessment, state.tripDraft.zoneId)
                else -> Unit
            }
        }
    }
}

@Composable
private fun TripResultContent(assessment: TripAssessment, zoneId: ZoneId) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { OverallTripCard(assessment) }
        if (assessment.horizonWarning != null) {
            item { InfoCard("Forecast horizon", assessment.horizonWarning) }
        }
        if (assessment.limitViolations.isNotEmpty()) {
            item { InfoCard("Trip too complex", assessment.limitViolations.joinToString("\n") { it.message }) }
        }
        item { TripTimelineCard(assessment, zoneId) }
        if (assessment.worstHazards.isNotEmpty()) {
            item { HazardsCard(assessment) }
        }
        item { ConfidenceCard(assessment) }
        item { SafetyFooter() }
    }
}

@Composable
private fun OverallTripCard(assessment: TripAssessment) {
    val category = assessment.overallCategory
    val color = category.toColor()
    val plan = assessment.plan
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("", color = MaterialTheme.colorScheme.background)
                }
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        category.label().uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.headlineSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${plan.departure.name} → ${plan.destination.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            assessment.mainConcern?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Main concern", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Planning distance: ${String.format(Locale.US, "%.1f", com.wakewindow.app.domain.trip.TripLegEstimator.totalPlanningDistanceNm(plan))} NM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The core Mode B UI requirement: a chronological timeline that keeps a user-chosen planning
 * waypoint visually and textually distinct from a generated weather sample - see
 * docs/TRIP_PLANNING.md's UI sketch. Never renders a line/route between points - see that same
 * doc's "no fake channels or routing lines."
 */
@Composable
private fun TripTimelineCard(assessment: TripAssessment, zoneId: ZoneId) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Timeline", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            assessment.timeline.forEach { point -> TripPointRow(point, zoneId) }
        }
    }
}

@Composable
private fun TripPointRow(point: TripPointAssessment, zoneId: ZoneId) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(formatTime(point.at, zoneId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(point.displayName(), style = MaterialTheme.typography.titleSmall)
            }
            Surface(color = point.category.toColor(), shape = MaterialTheme.shapes.small) {
                Text(
                    point.category.label(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.background,
                )
            }
        }
        point.hazards.firstOrNull()?.let { hazard ->
            Text(
                hazard.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun TripPointAssessment.displayName(): String = when (kind) {
    TripPointKind.DEPARTURE -> name?.let { "Departure · $it" } ?: "Departure"
    TripPointKind.DESTINATION -> name?.let { "Destination · $it" } ?: "Destination"
    TripPointKind.WAYPOINT -> name ?: "Planning waypoint"
    TripPointKind.WEATHER_SAMPLE -> "Weather sample"
}

@Composable
private fun HazardsCard(assessment: TripAssessment) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Hazards along your plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            assessment.worstHazards.take(6).forEach { hazard ->
                Text("• ${hazard.message}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun ConfidenceCard(assessment: TripAssessment) {
    val confidence = assessment.confidence
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${confidence.level.name} CONFIDENCE",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (confidence.reasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                confidence.reasons.distinct().forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SafetyFooter() {
    Text(
        "WakeWindow estimates conditions between user-selected planning points. It does not calculate a navigable marine route, and is a planning aid only - it does not replace official marine forecasts, nautical charts, Notices to Mariners, local authorities, or responsible seamanship.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

private fun formatTime(instant: java.time.Instant, zoneId: ZoneId): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    return ZonedDateTime.ofInstant(instant, zoneId).format(formatter)
}
