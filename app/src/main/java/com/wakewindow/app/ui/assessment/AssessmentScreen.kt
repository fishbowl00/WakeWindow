package com.wakewindow.app.ui.assessment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.scoring.BoatingWindowAssessment
import com.wakewindow.app.domain.scoring.Hazard
import com.wakewindow.app.domain.scoring.PointAssessment
import com.wakewindow.app.domain.scoring.severityRank
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { OverallCard(assessment) }
        assessment.bestWindow?.let { window ->
            item {
                InfoCard(title = "Best boating window") {
                    Text(
                        "${formatTime(window.start, zoneId)} - ${formatTime(window.end, zoneId)}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Average score ${window.averageScore}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { ConditionsGrid(assessment.departureAssessment) }
        item { WindowBreakdownCard(assessment) }
        if (assessment.worstHazards.isNotEmpty()) {
            item { HazardsCard(assessment.worstHazards, zoneId) }
        }
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

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            content()
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
                MetricColumn("Tide", c?.tideTrend?.name?.lowercase(Locale.getDefault())?.replaceFirstChar { it.uppercase() } ?: "—", c?.tideHeightFt?.let { "${String.format("%.1f", it)} ft" })
                MetricColumn("Storm", c?.thunderstormProbabilityPercent?.let { "$it%" } ?: "—", null)
            }
        }
    }
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
private fun ConfidenceCard(assessment: BoatingWindowAssessment) {
    val confidence = assessment.confidence
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Data confidence: ${confidence.level.displayName()}",
                style = MaterialTheme.typography.labelLarge,
            )
            confidence.reasons.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
