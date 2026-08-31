package com.wakewindow.app.ui.planboat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.route.QuickPlanKind
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.ui.WakeWindowUiState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanBoatScreen(
    state: WakeWindowUiState,
    onDepartureChange: (Instant) -> Unit,
    onReturnChange: (Instant) -> Unit,
    onVesselChange: (VesselProfile) -> Unit,
    onEditVessel: () -> Unit,
    onQuickPlan: (QuickPlanKind) -> Unit,
    onShowConditions: () -> Unit,
    onBack: () -> Unit,
) {
    var editingDate by remember { mutableStateOf(false) }
    var editingDeparture by remember { mutableStateOf(false) }
    var editingReturn by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.activeLaunch?.place?.name ?: "Plan your outing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.activeLaunch != null && state.departureTime != null && state.returnTime != null) {
                PlanSummaryCard(state)
            }

            QuickPlanRow(onSelect = onQuickPlan)

            DateSelectCard(
                date = state.departureTime,
                zoneId = state.zoneId,
                onClick = { editingDate = true },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TimeSelectCard(
                    label = "Departure",
                    time = state.departureTime,
                    zoneId = state.zoneId,
                    onClick = { editingDeparture = true },
                    modifier = Modifier.weight(1f),
                )
                TimeSelectCard(
                    label = "Return",
                    time = state.returnTime,
                    zoneId = state.zoneId,
                    onClick = { editingReturn = true },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.departureTime != null && state.returnTime != null) {
                DurationLabel(state.departureTime, state.returnTime)
            }

            DaylightCard(state)

            VesselSelector(selected = state.vessel, available = state.availableVessels, onSelect = onVesselChange, onEdit = onEditVessel)

            Button(
                onClick = onShowConditions,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.departureTime != null && state.returnTime != null,
            ) {
                Text("Show boating conditions")
            }
        }
    }

    if (editingDate && state.departureTime != null && state.returnTime != null) {
        DateSelectDialog(
            initial = state.departureTime,
            zoneId = state.zoneId,
            onConfirm = { newDate ->
                val dayDelta = Duration.between(
                    state.departureTime.atZone(state.zoneId).toLocalDate().atStartOfDay(state.zoneId).toInstant(),
                    newDate.atZone(state.zoneId).toLocalDate().atStartOfDay(state.zoneId).toInstant(),
                )
                onDepartureChange(state.departureTime.plus(dayDelta))
                onReturnChange(state.returnTime.plus(dayDelta))
                editingDate = false
            },
            onDismiss = { editingDate = false },
        )
    }
    if (editingDeparture && state.departureTime != null) {
        TimePickerDialog(
            initial = state.departureTime,
            zoneId = state.zoneId,
            onConfirm = { onDepartureChange(it); editingDeparture = false },
            onDismiss = { editingDeparture = false },
        )
    }
    if (editingReturn && state.returnTime != null) {
        TimePickerDialog(
            initial = state.returnTime,
            zoneId = state.zoneId,
            onConfirm = { onReturnChange(it); editingReturn = false },
            onDismiss = { editingReturn = false },
        )
    }
}

/**
 * Makes the currently-active plan obvious at a glance before the user digs into individual
 * fields - see docs/PLANNING.md "Plan summary." Only shown once a launch, departure, and
 * return are all set (i.e. there's an actual plan to summarize).
 */
@Composable
private fun PlanSummaryCard(state: WakeWindowUiState) {
    val departure = state.departureTime ?: return
    val returnTime = state.returnTime ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(state.activeLaunch?.place?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatDate(departure, state.zoneId)} · ${formatTime(departure, state.zoneId)} → ${formatTime(returnTime, state.zoneId)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(state.vessel.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shortcut departure/return windows - see docs/PLANNING.md "Quick plans." Selecting one
 * immediately fills in the manual pickers below rather than replacing them, so the result is
 * always visible and still freely editable afterward. */
@Composable
private fun QuickPlanRow(onSelect: (QuickPlanKind) -> Unit) {
    Column {
        Text("Quick plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                QuickPlanKind.MORNING to "Morning",
                QuickPlanKind.AFTERNOON to "Afternoon",
                QuickPlanKind.EVENING to "Evening",
                QuickPlanKind.FULL_DAY to "Full day",
            ).forEach { (kind, label) ->
                FilterChip(selected = false, onClick = { onSelect(kind) }, label = { Text(label) })
            }
        }
    }
}

/**
 * Real sunrise/sunset context for the departure date - see docs/PLANNING.md "Daylight
 * context" and [com.wakewindow.app.domain.sun.SolarCalculator]. Informational only: a return
 * after sunset is noted, never treated as unsafe on its own.
 */
@Composable
private fun DaylightCard(state: WakeWindowUiState) {
    val sunTimes = state.sunTimes ?: return
    val sunrise = sunTimes.sunrise
    val sunset = sunTimes.sunset
    if (sunrise == null && sunset == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                sunrise?.let {
                    Column {
                        Text("Sunrise", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTime(it, state.zoneId), style = MaterialTheme.typography.titleMedium)
                    }
                }
                sunset?.let {
                    Column {
                        Text("Sunset", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTime(it, state.zoneId), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            state.returnAfterSunsetMinutes?.let { minutes ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Your planned return is $minutes min after sunset",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun DateSelectCard(date: Instant?, zoneId: ZoneId, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Date", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                date?.let { formatDate(it, zoneId) } ?: "Not set",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TimeSelectCard(label: String, time: Instant?, zoneId: ZoneId, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                time?.let { formatTime(it, zoneId) } ?: "Not set",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Compact, single-row preset picker - see docs/MARINE_SCORING.md "Vessel profiles." Selecting
 * a vessel only changes which tolerances scoring uses; it can never override an active marine
 * warning gate or hide a hazard already found for the current conditions.
 */
@Composable
private fun VesselSelector(selected: VesselProfile, available: List<VesselProfile>, onSelect: (VesselProfile) -> Unit, onEdit: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Vessel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onEdit) { Text(if (selected.isCustom) "Edit" else "Customize") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            available.forEach { candidate ->
                FilterChip(
                    selected = candidate.id == selected.id,
                    onClick = { onSelect(candidate) },
                    label = { Text(candidate.name) },
                )
            }
        }
    }
}

@Composable
private fun DurationLabel(departure: Instant, returnTime: Instant) {
    val minutes = Duration.between(departure, returnTime).toMinutes().coerceAtLeast(0)
    val hours = minutes / 60
    val remMinutes = minutes % 60
    val text = if (remMinutes == 0L) "Outing duration: ${hours}h" else "Outing duration: ${hours}h ${remMinutes}m"
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelectDialog(
    initial: Instant,
    zoneId: ZoneId,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = initial.atZone(zoneId).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val selectedMillis = pickerState.selectedDateMillis
                if (selectedMillis != null) {
                    onConfirm(Instant.ofEpochMilli(selectedMillis))
                } else {
                    onDismiss()
                }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: Instant,
    zoneId: ZoneId,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val zoned = initial.atZone(zoneId)
    val pickerState = rememberTimePickerState(initialHour = zoned.hour, initialMinute = zoned.minute, is24Hour = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val updated = zoned.withHour(pickerState.hour).withMinute(pickerState.minute).withSecond(0).withNano(0)
                onConfirm(updated.toInstant())
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = pickerState) },
    )
}

private fun formatTime(instant: Instant, zoneId: ZoneId): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    return ZonedDateTime.ofInstant(instant, zoneId).format(formatter)
}

private fun formatDate(instant: Instant, zoneId: ZoneId): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    return ZonedDateTime.ofInstant(instant, zoneId).format(formatter)
}
