package com.wakewindow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.wakewindow.app.domain.settings.AppearanceMode
import com.wakewindow.app.ui.WakeWindowApp
import com.wakewindow.app.ui.splash.InknautSplashScreen
import com.wakewindow.app.ui.theme.WakeWindowTheme

class MainActivity : ComponentActivity() {

    private var composeSplashDrawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashScreen.setKeepOnScreenCondition { !composeSplashDrawn }

        setContent {
            // TODO(next sprint): resolve from persisted settings once implemented.
            val appearanceMode = AppearanceMode.SYSTEM
            var showSplash by remember { mutableStateOf(true) }

            WakeWindowTheme(appearanceMode = appearanceMode) {
                if (showSplash) {
                    InknautSplashScreen(
                        isDarkTheme = isSystemInDarkTheme(),
                        onComposed = { composeSplashDrawn = true },
                        onFinished = { showSplash = false },
                    )
                } else {
                    WakeWindowApp()
                }
            }
        }
    }
}
