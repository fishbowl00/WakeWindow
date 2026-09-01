package com.wakewindow.app.ui.tripplan

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.trip.PlanningWaypoint
import com.wakewindow.app.domain.trip.TripLegEstimator
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.ui.WakeWindowUiState
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Mode B trip editor - see docs/TRIP_PLANNING.md. Deliberately reuses [com.wakewindow.app.ui.planboat.PlanBoatScreen]'s
 * card/dialog vocabulary (date/time pickers, vessel chip row) rather than inventing new UI
 * patterns for what's conceptually the same kind of picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripPlanScreen(
    state: WakeWindowUiState,
    onSetName: (String) -> Unit,
    onSearchDeparture: () -> Unit,
    onSearchDestination: () -> Unit,
    onAddWaypoint: () -> Unit,
    onRemoveWaypoint: (String) -> Unit,
    onDepartureTimeChange: (Instant) -> Unit,
    onVesselChange: (VesselProfile) -> Unit,
    onCruiseSpeedChange: (Double?) -> Unit,
    onNotesChange: (String) -> Unit,
    onAssessTrip: () -> Unit,
    onBack: () -> Unit,
) {
    val draft = state.tripDraft
    var editingDate by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plan a trip") },
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
            NonNavigationDisclaimer()

            OutlinedTextField(
                value = draft.name,
                onValueChange = onSetName,
                label = { Text("Trip name (optional)") },
                placeholder = { Text("e.g. Canaveral to Sebastian") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            PlacePickerCard(label = "From", waypoint = draft.departure, onClick = onSearchDeparture)
            PlacePickerCard(label = "To", waypoint = draft.destination, onClick = onSearchDestination)

            DateSelectCard(date = draft.departureTime, zoneId = draft.zoneId, onClick = { editingDate = true })
            TimeSelectCard(label = "Departure", time = draft.departureTime, zoneId = draft.zoneId, onClick = { editingTime = true })

            VesselSelector(selected = draft.vessel ?: state.vessel, available = state.availableVessels, onSelect = onVesselChange)

            CruiseSpeedField(value = draft.cruiseSpeedKts, onChange = onCruiseSpeedChange)

            WaypointsSection(waypoints = draft.waypoints, onAdd = onAddWaypoint, onRemove = onRemoveWaypoint)

            OutlinedTextField(
                value = draft.notes.orEmpty(),
                onValueChange = onNotesChange,
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            PlanningSummary(draft)

            Button(
                onClick = onAssessTrip,
                modifier = Modifier.fillMaxWidth(),
                enabled = draft.departure != null && draft.destination != null && draft.departureTime != null,
            ) {
                Text("Assess trip")
            }
        }
    }

    if (editingDate && draft.departureTime != null) {
        DateSelectDialog(
            initial = draft.departureTime,
            zoneId = draft.zoneId,
            onConfirm = { newDate ->
                val dayDelta = java.time.Duration.between(
                    draft.departureTime.atZone(draft.zoneId).toLocalDate().atStartOfDay(draft.zoneId).toInstant(),
                    newDate.atZone(draft.zoneId).toLocalDate().atStartOfDay(draft.zoneId).toInstant(),
                )
                onDepartureTimeChange(draft.departureTime.plus(dayDelta))
                editingDate = false
            },
            onDismiss = { editingDate = false },
        )
    }
    if (editingTime && draft.departureTime != null) {
        TimePickerDialog(
            initial = draft.departureTime,
            zoneId = draft.zoneId,
            onConfirm = { onDepartureTimeChange(it); editingTime = false },
            onDismiss = { editingTime = false },
        )
    }
}

/** Phase 4's mandated non-navigation disclaimer - kept close to the editor, not buried, and
 * using the sprint brief's own exact wording so it never drifts from what was reviewed. */
@Composable
private fun NonNavigationDisclaimer() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            "WakeWindow estimates conditions between user-selected planning points. It does not calculate a navigable marine route.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun PlacePickerCard(label: String, waypoint: PlanningWaypoint?, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                waypoint?.name ?: "Choose a place",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DateSelectCard(date: Instant?, zoneId: ZoneId, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Departure date", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(date?.let { formatDate(it, zoneId) } ?: "Not set", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TimeSelectCard(label: String, time: Instant?, zoneId: ZoneId, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time?.let { formatTime(it, zoneId) } ?: "Not set", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun VesselSelector(selected: VesselProfile, available: List<VesselProfile>, onSelect: (VesselProfile) -> Unit) {
    Column {
        Text("Vessel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            available.forEach { candidate ->
                FilterChip(selected = candidate.id == selected.id, onClick = { onSelect(candidate) }, label = { Text(candidate.name) })
            }
        }
    }
}

@Composable
private fun CruiseSpeedField(value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            onChange(newText.toDoubleOrNull())
        },
        label = { Text("Cruise speed (kts)") },
        placeholder = { Text("e.g. 22") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Manual planning waypoints, distinct throughout from any generated weather-sample point (see
 * docs/TRIP_PLANNING.md "Weather samples vs. planning waypoints") - every entry here was chosen
 * by the user, nothing else ever appears in this list.
 */
@Composable
private fun WaypointsSection(waypoints: List<PlanningWaypoint>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Column {
        Text("Waypoints", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        waypoints.forEach { waypoint ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(waypoint.name, style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onRemove(waypoint.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove ${waypoint.name}")
                }
            }
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.height(0.dp))
            Text("Add planning waypoint", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** A live preview of planning distance/estimated duration as the plan fills in - built from the
 * exact same [TripLegEstimator] the assessment itself uses, so this number is never a separate,
 * independently-computed estimate. */
@Composable
private fun PlanningSummary(draft: com.wakewindow.app.ui.trip.TripDraft) {
    val departure = draft.departure ?: return
    val destination = draft.destination ?: return
    val departureTime = draft.departureTime ?: return
    val plan = com.wakewindow.app.domain.trip.MarineTripPlan(
        departure = departure,
        destination = destination,
        departureTime = departureTime,
        vessel = draft.vessel ?: VesselProfile.default(),
        zoneId = draft.zoneId,
        waypoints = draft.waypoints,
        cruiseSpeedKts = draft.cruiseSpeedKts,
    )
    val totalNm = TripLegEstimator.totalPlanningDistanceNm(plan)
    val duration = TripLegEstimator.estimatedDuration(plan)
    val hasSpeed = draft.cruiseSpeedKts != null && draft.cruiseSpeedKts > 0.0
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Planning distance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${String.format(Locale.US, "%.1f", totalNm)} NM", style = MaterialTheme.typography.titleMedium)
            if (hasSpeed) {
                val hours = duration.toMinutes() / 60.0
                Text(
                    "Estimated duration: ${String.format(Locale.US, "%.1f", hours)} h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Add a cruise speed to estimate arrival times at each point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelectDialog(initial: Instant, zoneId: ZoneId, onConfirm: (Instant) -> Unit, onDismiss: () -> Unit) {
    val initialMillis = initial.atZone(zoneId).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val selectedMillis = pickerState.selectedDateMillis
                if (selectedMillis != null) onConfirm(Instant.ofEpochMilli(selectedMillis)) else onDismiss()
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = pickerState) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initial: Instant, zoneId: ZoneId, onConfirm: (Instant) -> Unit, onDismiss: () -> Unit) {
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
