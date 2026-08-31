package com.wakewindow.app.ui.launchinfo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.place.FacilityAvailability
import com.wakewindow.app.domain.place.FacilityOperationalStatus
import com.wakewindow.app.domain.place.MarineFacilityInfo
import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.ui.WakeWindowUiState
import com.wakewindow.app.ui.theme.CategoryColors
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Works honestly even when almost nothing is known - see docs/PRODUCT.md "Facility data
 * states." Every visible fact is real (AVAILABLE/NOT_AVAILABLE/a real value) - genuinely
 * [FacilityAvailability.UNKNOWN] fields are hidden from the normal reading order rather than
 * padding every section with "Unknown" rows, and are still reachable (honestly labeled
 * "Unknown," never blank or guessed) behind each section's "More information" toggle. See
 * docs/PLACE_DISCOVERY.md and docs/DATA_SOURCES.md "Marine place / launch intelligence" for
 * where real data now comes from (Florida FWC boat ramps) vs. where it's still the honest
 * all-unknown default (everything else).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchInfoScreen(state: WakeWindowUiState, onBack: () -> Unit) {
    val launch = state.infoLaunch
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(launch?.place?.name ?: "Launch information") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (launch == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HeaderCard(launch.place, isLoading = state.isLoadingFacilityInfo) }
            item { AccessCard(launch.place.facility) }
            item { FacilitiesCard(launch.place.facility) }
            if (launch.place.facility.phone != null || launch.place.facility.website != null || launch.place.facility.harborMasterPhone != null) {
                item { ContactCard(launch.place.facility) }
            }
            item { LocationCard(launch.place) }
            item { SourceCard(launch.place.facility, error = state.facilityInfoError) }
        }
    }
}

@Composable
private fun HeaderCard(place: MarinePlace, isLoading: Boolean) {
    val facility = place.facility
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(place.name, style = MaterialTheme.typography.headlineSmall)
            val subtitle = listOfNotNull(place.type.label(), facility.waterBodyName).joinToString(" · ")
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            place.discovery.address?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (facility.hasAnyVerifiedData && facility.source?.isOfficial == true) {
                    Badge(facility.source.sourceName, MaterialTheme.colorScheme.primary)
                }
                operationalStatusBadge(facility.operationalStatus)?.let { (label, color) -> Badge(label, color) }
                if (isLoading) {
                    Spacer(modifier = Modifier.width(4.dp))
                    CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Text("Checking official source…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val chips = knownFactChips(facility)
            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chips) { chip -> FactChip(chip) }
                }
            }
        }
    }
}

/** Compact positive/quantitative facts worth a glance before reading any section in full -
 * unknowns and negatives never appear here, only real known-good facts (see the sprint's
 * "Facility data UX" guidance: "✓ Restrooms", "2 ramp lanes"). */
private fun knownFactChips(facility: MarineFacilityInfo): List<String> = buildList {
    facility.rampLanes?.let { add(if (it == 1) "1 ramp lane" else "$it ramp lanes") }
    if (facility.trailerParking == FacilityAvailability.AVAILABLE) add("✓ Trailer parking")
    if (facility.floatingDock == FacilityAvailability.AVAILABLE) add("✓ Dock")
    if (facility.restroom == FacilityAvailability.AVAILABLE) add("✓ Restrooms")
    if (facility.fuel == FacilityAvailability.AVAILABLE) add("✓ Fuel")
    if (facility.freshwater == FacilityAvailability.AVAILABLE) add("✓ Freshwater")
    if (facility.pumpOut == FacilityAvailability.AVAILABLE) add("✓ Pump-out")
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FactChip(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

private fun operationalStatusBadge(status: FacilityOperationalStatus): Pair<String, Color>? = when (status) {
    FacilityOperationalStatus.OPEN -> "Open" to CategoryColors.Good
    FacilityOperationalStatus.CLOSED -> "Closed" to CategoryColors.Poor
    FacilityOperationalStatus.PARTIALLY_OPEN -> "Partially open" to CategoryColors.Caution
    FacilityOperationalStatus.SEASONAL -> "Seasonal" to CategoryColors.Caution
    FacilityOperationalStatus.UNKNOWN -> null
}

/** A row of known facts, plus - only if any exist - a toggle revealing the section's unknown
 * fields explicitly as "Unknown" (never blank, never silently dropped, just not first-class
 * reading order). If a section has literally nothing known, it says so plainly instead of
 * looking broken. */
@Composable
private fun InfoSection(title: String, known: List<Pair<String, String>>, unknownLabels: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            if (known.isEmpty()) {
                Text(
                    "No official information available yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                known.forEach { (label, value) -> InfoRow(label, value) }
            }
            if (unknownLabels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "More information not available (${unknownLabels.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide unavailable fields" else "Show unavailable fields",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        unknownLabels.forEach { label -> InfoRow(label, "Unknown") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessCard(facility: MarineFacilityInfo) {
    val known = buildList {
        facility.hours?.let { add("Hours" to it) }
        facility.launchFee?.let { add("Launch fee" to it) }
        facility.parkingFee?.let { add("Parking fee" to it) }
        facility.rampLanes?.let { add("Ramp lanes" to it.toString()) }
        facility.rampType?.let { add("Ramp type" to it) }
        facility.accessType?.let { add("Access type" to it) }
        if (facility.trailerParking.isKnown()) add("Trailer parking" to facility.trailerParking.label())
        if (facility.floatingDock.isKnown()) add("Dock" to facility.floatingDock.label())
        facility.gateHours?.let { add("Gate hours" to it) }
        facility.launchRestrictions?.let { add("Launch restrictions" to it) }
        facility.vesselRestrictions?.let { add("Vessel restrictions" to it) }
    }
    val unknown = buildList {
        if (facility.hours == null) add("Hours")
        if (facility.launchFee == null) add("Launch fee")
        if (facility.parkingFee == null) add("Parking fee")
        if (facility.rampLanes == null) add("Ramp lanes")
        if (!facility.trailerParking.isKnown()) add("Trailer parking")
        if (!facility.floatingDock.isKnown()) add("Dock")
    }
    InfoSection("Access", known, unknown)
}

@Composable
private fun FacilitiesCard(facility: MarineFacilityInfo) {
    val known = buildList {
        if (facility.restroom.isKnown()) add("Restrooms" to facility.restroom.label())
        if (facility.fuel.isKnown()) add("Fuel" to facility.fuel.label())
        if (facility.freshwater.isKnown()) add("Freshwater" to facility.freshwater.label())
        if (facility.pumpOut.isKnown()) add("Pump-out" to facility.pumpOut.label())
        if (facility.transientSlips.isKnown()) add("Transient slips" to facility.transientSlips.label())
        facility.transientSlipCost?.let { add("Transient slip cost" to it) }
        if (facility.mooring.isKnown()) add("Mooring" to facility.mooring.label())
        facility.mooringCost?.let { add("Mooring cost" to it) }
        if (facility.reservationRequired.isKnown()) add("Reservation required" to facility.reservationRequired.label())
        facility.amenitiesRaw?.let { add("Other amenities" to it) }
    }
    val unknown = buildList {
        if (!facility.restroom.isKnown()) add("Restrooms")
        if (!facility.fuel.isKnown()) add("Fuel")
        if (!facility.freshwater.isKnown()) add("Freshwater")
        if (!facility.pumpOut.isKnown()) add("Pump-out")
    }
    InfoSection("Facilities", known, unknown)
}

@Composable
private fun ContactCard(facility: MarineFacilityInfo) {
    val known = buildList {
        facility.phone?.let { add("Phone" to it) }
        facility.website?.let { add("Website" to it) }
        facility.harborMasterPhone?.let { add("Harbor master phone" to it) }
        facility.harborMasterChannel?.let { add("Harbor master VHF" to it) }
        facility.vhfCallingChannel?.let { add("VHF calling channel" to it) }
    }
    InfoSection("Contact", known, emptyList())
}

@Composable
private fun LocationCard(place: MarinePlace) {
    val facility = place.facility
    val known = buildList {
        place.discovery.address?.let { add("Address" to it) }
        facility.waterBodyName?.let { add("Waterbody" to it) }
        add("Coordinates" to "${String.format(Locale.US, "%.5f", place.location.latitude)}, ${String.format(Locale.US, "%.5f", place.location.longitude)}")
    }
    InfoSection("Location", known, emptyList())
}

@Composable
private fun SourceCard(facility: MarineFacilityInfo, error: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Source", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            val source = facility.source
            when {
                error != null -> Text(
                    "Couldn't check the official source this time ($error). Showing the last known information, if any.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                source == null -> Text(
                    "No verified facility information available for this launch. Contact the marina or harbor authority directly to confirm current hours, fees, and restrictions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    InfoRow("Source", source.sourceName)
                    source.sourceUrl?.let { InfoRow("Link", it) }
                    InfoRow(
                        "Last checked",
                        source.verifiedAt?.let { formatDate(it) } ?: formatDate(source.retrievedAt),
                    )
                    facility.operationalStatusRaw?.let { InfoRow("Status as reported", it) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

private fun MarinePlaceType.label(): String = when (this) {
    MarinePlaceType.BOAT_RAMP -> "Boat ramp"
    MarinePlaceType.MARINA -> "Marina"
    MarinePlaceType.HARBOR -> "Harbor"
    MarinePlaceType.PORT -> "Port"
    MarinePlaceType.DOCK -> "Dock"
    MarinePlaceType.YACHT_CLUB -> "Yacht club"
    MarinePlaceType.ANCHORAGE -> "Anchorage"
    MarinePlaceType.OTHER -> "Launch"
}

/** [FacilityAvailability.UNKNOWN] is the only state that's ever hidden from normal reading
 * order - see the class doc. [FacilityAvailability.NOT_APPLICABLE] is itself a known, useful
 * fact ("this place has no docking at all") and is shown, not hidden. */
private fun FacilityAvailability.isKnown(): Boolean = this != FacilityAvailability.UNKNOWN

private fun FacilityAvailability.label(): String = when (this) {
    FacilityAvailability.AVAILABLE -> "Available"
    FacilityAvailability.NOT_AVAILABLE -> "Not available"
    FacilityAvailability.UNKNOWN -> "Unknown"
    FacilityAvailability.NOT_APPLICABLE -> "Not applicable"
}

private fun formatDate(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).format(formatter)
}
