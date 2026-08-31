package com.wakewindow.app.ui.planboat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "When do you want to leave, and when do you expect to be back?",
                style = MaterialTheme.typography.titleMedium,
            )

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
