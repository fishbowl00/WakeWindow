package com.wakewindow.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wakewindow.app.ui.navigation.WakeWindowNavHost

@Composable
fun WakeWindowApp() {
    val viewModel: WakeWindowViewModel = viewModel()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        WakeWindowNavHost(viewModel = viewModel)
    }
}
