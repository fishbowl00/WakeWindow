package com.wakewindow.app.ui.vessel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.domain.vessel.VesselType
import com.wakewindow.app.ui.WakeWindowUiState

/**
 * Lets a boater describe the boat they actually own, rather than only picking from the five
 * built-in presets - see docs/VESSEL_PROFILES.md. Every threshold here is explicitly a
 * *planning preference*, never a manufacturer operating limit or a safety certification - see
 * the disclaimer in [PlanningPreferencesCard] - and none of them can ever neutralize an active
 * official marine warning, which always gates independently in
 * [com.wakewindow.app.domain.scoring.MarinePointScorer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VesselProfileScreen(
    state: WakeWindowUiState,
    onSelectVessel: (VesselProfile) -> Unit,
    onSaveProfile: (VesselProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(state.vessel.id) { mutableStateOf(state.vessel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vessel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text("Start from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.availableVessels.forEach { candidate ->
                            FilterChip(
                                selected = candidate.id == draft.id,
                                onClick = {
                                    onSelectVessel(candidate)
                                    draft = candidate
                                },
                                label = { Text(if (candidate.isCustom) "${candidate.name} ✎" else candidate.name) },
                            )
                        }
                    }
                }
            }
            item { CustomizeCard(draft, onChange = { draft = it }) }
            item { PlanningPreferencesCard(draft, onChange = { draft = it }) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveProfile(draft.markCustomized()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (draft.isCustom) "Save changes" else "Save as a new profile")
                    }
                    // The base this edit started from (before any local, unsaved changes) - a
                    // preset only if the user hadn't already picked a saved custom profile.
                    val basePreset = state.vessel.takeIf { !it.isCustom }
                    if (basePreset != null && draft.id != basePreset.id) {
                        OutlinedButton(onClick = { draft = basePreset }, modifier = Modifier.fillMaxWidth()) {
                            Text("Reset to preset values")
                        }
                    }
                    if (state.vessel.isCustom) {
                        TextButton(onClick = { onDeleteProfile(state.vessel.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete this profile", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeCard(draft: VesselProfile, onChange: (VesselProfile) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your boat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.markCustomized().copy(name = it)) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VesselType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.vesselType == type,
                            onClick = { onChange(draft.markCustomized().copy(vesselType = type)) },
                            label = { Text(type.label()) },
                        )
                    }
                }
            }
            NumberField(
                label = "Length (ft)",
                value = draft.lengthFt,
                onValueChange = { onChange(draft.markCustomized().copy(lengthFt = it)) },
            )
        }
    }
}

/**
 * Every field here is a *planning preference*, never a manufacturer-rated safe operating
 * limit and never a seaworthiness certification - see docs/VESSEL_PROFILES.md. These values
 * cannot account for loading, hull condition, skipper experience, or actual local sea state,
 * and an official marine warning always takes precedence over a favorable vessel preference -
 * see [com.wakewindow.app.domain.scoring.MarinePointScorer].
 */
@Composable
private fun PlanningPreferencesCard(draft: VesselProfile, onChange: (VesselProfile) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Planning preferences", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Comfort thresholds for this plan, not manufacturer-rated safe operating limits or a seaworthiness certification. They don't account for loading, hull condition, or skipper experience. An official marine warning always takes precedence, no matter how these are set.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField("Preferred max sustained wind (kt)", draft.windToleranceKts) { onChange(draft.markCustomized().copy(windToleranceKts = it ?: draft.windToleranceKts)) }
            NumberField("Preferred max gust (kt)", draft.gustToleranceKts) { onChange(draft.markCustomized().copy(gustToleranceKts = it ?: draft.gustToleranceKts)) }
            NumberField("Preferred max wave height (ft)", draft.waveToleranceFt) { onChange(draft.markCustomized().copy(waveToleranceFt = it ?: draft.waveToleranceFt)) }
            NumberField("Visibility threshold (NM)", draft.visibilityToleranceNm) { onChange(draft.markCustomized().copy(visibilityToleranceNm = it ?: draft.visibilityToleranceNm)) }
            IntField("Thunderstorm sensitivity (%)", draft.thunderstormTolerancePercent) { onChange(draft.markCustomized().copy(thunderstormTolerancePercent = it ?: draft.thunderstormTolerancePercent)) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Double?, onValueChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { formatNumber(it) } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            onValueChange(newText.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntField(label: String, value: Int, onValueChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            onValueChange(newText.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun VesselType.label(): String = when (this) {
    VesselType.SMALL_CENTER_CONSOLE -> "Center console"
    VesselType.BOWRIDER -> "Bowrider"
    VesselType.PONTOON -> "Pontoon"
    VesselType.FISHING_BOAT -> "Fishing boat"
    VesselType.SAILBOAT -> "Sailboat"
    VesselType.CRUISER -> "Cruiser"
    VesselType.PWC -> "PWC"
    VesselType.OTHER -> "Other"
}
