package com.wakewindow.app.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.wakewindow.app.BuildConfig
import com.wakewindow.app.R
import com.wakewindow.app.ui.theme.LocalWakeWindowDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .padding(24.dp),
        ) {
            Text("WakeWindow", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("Safety", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "WakeWindow is a planning aid and does not replace official marine forecasts, " +
                    "nautical charts, Notices to Mariners, local harbor authorities, or responsible " +
                    "seamanship. Conditions can change rapidly on the water - always check current " +
                    "official sources before and during your outing, and use your own judgment.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("Data sources", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Weather and marine forecasts: National Weather Service (api.weather.gov). " +
                    "Tide predictions: NOAA Tides & Currents. Development builds may also use " +
                    "Open-Meteo. Full source detail is documented in this project's DATA_SOURCES.md.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            PublisherAttribution()
        }
    }
}

@Composable
private fun PublisherAttribution() {
    val isDark = LocalWakeWindowDarkTheme.current
    val lockup = if (isDark) R.drawable.inknaut_lockup_stack_dark else R.drawable.inknaut_lockup_stack_light
    Image(
        painter = painterResource(id = lockup),
        contentDescription = "Inknaut Labs",
        modifier = Modifier.height(28.dp),
    )
}
