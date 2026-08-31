package com.wakewindow.app.ui.planboat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wakewindow.app.ui.WakeWindowUiState
import java.time.Instant
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

            TimeSelectCard(
                label = "Departure",
                time = state.departureTime,
                zoneId = state.zoneId,
                onClick = { editingDeparture = true },
            )
            TimeSelectCard(
                label = "Return",
                time = state.returnTime,
                zoneId = state.zoneId,
                onClick = { editingReturn = true },
            )

            Button(
                onClick = onShowConditions,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.departureTime != null && state.returnTime != null,
            ) {
                Text("Show boating conditions")
            }
        }
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
private fun TimeSelectCard(label: String, time: Instant?, zoneId: java.time.ZoneId, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: Instant,
    zoneId: java.time.ZoneId,
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

private fun formatTime(instant: Instant, zoneId: java.time.ZoneId): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    return ZonedDateTime.ofInstant(instant, zoneId).format(formatter)
}
