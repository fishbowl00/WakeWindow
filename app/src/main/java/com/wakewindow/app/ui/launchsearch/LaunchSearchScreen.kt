package com.wakewindow.app.ui.launchsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.PlaceSourceType
import com.wakewindow.app.ui.WakeWindowUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchSearchScreen(
    state: WakeWindowUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (MarinePlaceCandidate) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find a launch") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Ramp, marina, harbor name...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )

            when {
                state.isSearching -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.searchError != null -> Text(
                    state.searchError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.searchResults) { candidate ->
                        ListItem(
                            overlineContent = { Text(candidate.placeTypeLabel(), style = MaterialTheme.typography.labelSmall) },
                            headlineContent = { Text(candidate.name) },
                            supportingContent = candidate.address?.let { { Text(it) } },
                            leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(candidate) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Honest, source-aware labeling - see docs/PLACE_DISCOVERY.md "Ranking" and Sprint 3's mandate
 * to show place type "without inferring facility data." A boat ramp identified by FWC's own
 * inventory is a verified fact; the same label guessed from a generic geocoder's map tag is
 * not, and this line says so rather than presenting both the same way.
 */
private fun MarinePlaceCandidate.placeTypeLabel(): String {
    val type = guessedType.label()
    return when (sourceType) {
        PlaceSourceType.FWC_BOAT_RAMP -> "$type · FWC verified"
        PlaceSourceType.USACE_RECREATION_AREA -> "$type · USACE recreation area"
        PlaceSourceType.GEOCODING -> "$type · unverified"
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
    MarinePlaceType.OTHER -> "Place"
}
