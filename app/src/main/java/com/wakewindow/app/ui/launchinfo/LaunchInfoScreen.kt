package com.wakewindow.app.ui.launchinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.place.FacilityAvailability
import com.wakewindow.app.domain.place.MarineFacilityInfo
import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceType
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Works honestly even when almost nothing is known - see docs/PRODUCT.md "Facility data
 * states." Every row below renders one of AVAILABLE / NOT_AVAILABLE / UNKNOWN /
 * NOT_APPLICABLE (or "Not available" for a plain informational field) explicitly; nothing is
 * ever silently blank or guessed. No facility provider ships this sprint, so on a fresh
 * install every field will genuinely read "Unknown" - that is the correct, honest state, not
 * a bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchInfoScreen(place: MarinePlace, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(place.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HeaderCard(place) }
            item { FacilityCard(place.facility) }
            item { ContactCard(place.facility) }
            item { SourceCard(place.facility) }
        }
    }
}

@Composable
private fun HeaderCard(place: MarinePlace) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(place.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                place.type.label(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            place.discovery.address?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FacilityCard(facility: MarineFacilityInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Official information", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("Hours", facility.hours ?: "Not available")
            InfoRow("Launch fee", facility.launchFee ?: "Not available")
            InfoRow("Parking fee", facility.parkingFee ?: "Not available")
            InfoRow("Trailer parking", facility.trailerParking.label())
            InfoRow("Ramp lanes", facility.rampLanes?.toString() ?: "Not available")
            InfoRow("Floating dock", facility.floatingDock.label())
            InfoRow("Fuel", facility.fuel.label())
            InfoRow("Pump-out", facility.pumpOut.label())
            InfoRow("Restroom", facility.restroom.label())
            InfoRow("Freshwater", facility.freshwater.label())
            InfoRow("Transient slips", facility.transientSlips.label())
            InfoRow("Mooring", facility.mooring.label())
            InfoRow("Reservation required", facility.reservationRequired.label())
            InfoRow("Gate hours", facility.gateHours ?: "Not available")
            InfoRow("VHF channel", facility.vhfCallingChannel ?: "Not available")
            InfoRow("Harbor master channel", facility.harborMasterChannel ?: "Not available")
            facility.vesselRestrictions?.let { InfoRow("Vessel restrictions", it) }
            facility.launchRestrictions?.let { InfoRow("Launch restrictions", it) }
            facility.notes?.let { InfoRow("Notes", it) }
        }
    }
}

@Composable
private fun ContactCard(facility: MarineFacilityInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Contact", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("Phone", facility.phone ?: "Not available")
            InfoRow("Website", facility.website ?: "Not available")
            InfoRow("Harbor master phone", facility.harborMasterPhone ?: "Not available")
        }
    }
}

@Composable
private fun SourceCard(facility: MarineFacilityInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sources", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            val source = facility.source
            if (source == null) {
                Text(
                    "No verified facility information yet for this launch. Contact the marina or harbor authority directly to confirm current hours, fees, and restrictions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InfoRow("Source", source.sourceName)
                source.sourceUrl?.let { InfoRow("Link", it) }
                InfoRow(
                    "Last checked",
                    source.verifiedAt?.let { formatDate(it) } ?: formatDate(source.retrievedAt),
                )
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
