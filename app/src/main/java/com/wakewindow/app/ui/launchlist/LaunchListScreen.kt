package com.wakewindow.app.ui.launchlist

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.place.SavedLaunch
import com.wakewindow.app.ui.WakeWindowUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchListScreen(
    state: WakeWindowUiState,
    onAddLaunch: () -> Unit,
    onOpenLaunch: (SavedLaunch) -> Unit,
    onOpenLaunchInfo: (SavedLaunch) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WakeWindow") },
                actions = {
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "About WakeWindow")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddLaunch) {
                Icon(Icons.Filled.Add, contentDescription = "Add a launch")
            }
        },
    ) { innerPadding ->
        if (!state.hasAnySavedLaunch) {
            EmptyLaunchList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onAddLaunch = onAddLaunch,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.savedLaunches, key = { it.id }) { launch ->
                    LaunchCard(
                        launch = launch,
                        onClick = { onOpenLaunch(launch) },
                        onInfoClick = { onOpenLaunchInfo(launch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLaunchList(modifier: Modifier = Modifier, onAddLaunch: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.Anchor,
                contentDescription = null,
                modifier = Modifier.height(48.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Where are you launching?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Search for a boat ramp, marina, or harbor to get your first boating-day assessment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                onClick = onAddLaunch,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "Search for a launch" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        "Search launches",
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchCard(launch: SavedLaunch, onClick: () -> Unit, onInfoClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Anchor,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Text(launch.place.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                launch.place.discovery.address?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lastPlanSummary(launch)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            IconButton(onClick = onInfoClick) {
                Icon(Icons.Filled.Info, contentDescription = "Launch information for ${launch.place.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Usually 7 AM · 6h" - a real proxy for "faster the second time" (see
 * [com.wakewindow.app.domain.place.SavedLaunch] "Recent plans") without auto-firing a full
 * multi-provider assessment for every saved launch just because Home opened - that's real,
 * avoidable network cost the sprint brief explicitly warns against. Null until this launch has
 * ever actually been planned. */
private fun lastPlanSummary(launch: SavedLaunch): String? {
    val hour = launch.lastDepartureHourOfDay ?: return null
    val minutes = launch.lastDurationMinutes ?: return null
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val meridiem = if (hour < 12) "AM" else "PM"
    val durationHours = minutes / 60
    val durationLabel = if (minutes % 60 == 0L) "${durationHours}h" else "${durationHours}h ${minutes % 60}m"
    return "Usually $hour12 $meridiem · $durationLabel"
}
