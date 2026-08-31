package com.wakewindow.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Minimal MVP settings surface - units and appearance mode are architected for (see
 * domain/settings/AppSettings.kt) but not yet backed by a persisted, editable UI this
 * sprint. See docs/ROADMAP.md. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ListItem(
                headlineContent = { Text("Units") },
                supportingContent = { Text("Marine imperial - knots, feet, nautical miles") },
            )
            ListItem(
                headlineContent = { Text("Vessel profile") },
                supportingContent = { Text("Recreational boat (default)") },
            )
            ListItem(
                headlineContent = { Text("Appearance") },
                supportingContent = { Text("Follows system") },
            )
        }
    }
}
