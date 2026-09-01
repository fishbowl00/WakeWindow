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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import com.wakewindow.app.domain.trip.SavedTrip
import com.wakewindow.app.domain.trip.TripLegEstimator
import com.wakewindow.app.domain.trip.toPlan
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.ui.WakeWindowUiState
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchListScreen(
    state: WakeWindowUiState,
    onAddLaunch: () -> Unit,
    onOpenLaunch: (SavedLaunch) -> Unit,
    onOpenLaunchInfo: (SavedLaunch) -> Unit,
    onAddTrip: () -> Unit,
    onOpenTrip: (SavedTrip) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ExtendedFloatingActionButton(onClick = onAddTrip, icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("Trip") })
                FloatingActionButton(onClick = onAddLaunch) {
                    Icon(Icons.Filled.Add, contentDescription = "Plan a day outing")
                }
            }
        },
    ) { innerPadding ->
        if (!state.hasAnySavedLaunch && state.savedTrips.isEmpty()) {
            EmptyLaunchList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onAddLaunch = onAddLaunch,
                onAddTrip = onAddTrip,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.savedTrips.isNotEmpty()) {
                    item { SectionHeader("Saved trips") }
                    items(state.savedTrips, key = { "trip:${it.id}" }) { trip ->
                        TripCard(trip = trip, onClick = { onOpenTrip(trip) })
                    }
                }
                if (state.savedLaunches.isNotEmpty()) {
                    if (state.savedTrips.isNotEmpty()) item { SectionHeader("Saved launches") }
                    items(state.savedLaunches, key = { "launch:${it.id}" }) { launch ->
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
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyLaunchList(modifier: Modifier = Modifier, onAddLaunch: () -> Unit, onAddTrip: () -> Unit) {
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
                "Search for a boat ramp, marina, or harbor to get your first boating-day assessment - or plan a multi-point trip.",
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
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                onClick = onAddTrip,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.semantics { contentDescription = "Plan a trip" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "Plan a trip",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
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

/** The Mode B counterpart to [LaunchCard] - see docs/TRIP_PLANNING.md "Saved trips" and the
 * sprint brief's Phase 15 example ("Canaveral -> Sebastian / Last used Aug 31"). Never runs a
 * full multi-provider trip assessment just because Home rendered a card - see
 * [com.wakewindow.app.ui.WakeWindowViewModel.selectSavedTrip], which only builds a live plan
 * once the user actually opens this trip. */
@Composable
private fun TripCard(trip: SavedTrip, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(trip.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${trip.departure.name} → ${trip.destination.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${String.format(Locale.US, "%.0f", TripLegEstimator.totalPlanningDistanceNm(trip.toPlan(VesselProfile.default(), Instant.now())))} NM · ${lastTripSummary(trip)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun lastTripSummary(trip: SavedTrip): String {
    val hour = trip.lastDepartureHourOfDay
    if (hour == null) return "Not yet run"
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val meridiem = if (hour < 12) "AM" else "PM"
    return "Usually $hour12 $meridiem"
}
