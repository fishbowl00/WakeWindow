package com.wakewindow.app.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.wakewindow.app.R
import kotlinx.coroutines.delay

/**
 * App-phase splash: the Inknaut Labs publisher mark, on the same background color as the
 * native (pre-Compose) splash theme (see themes.xml / values-night/themes.xml), so there is
 * no visible seam when the platform SplashScreen hands off to this composable. Held for a
 * fixed duration - decoupled from whether app data/settings have finished loading, which
 * [onFinished] is separately gated on by the caller. See docs/RIDECAST_REFERENCE_AUDIT.md
 * section 1/5 for why this two-phase, icon-less-native-layer approach avoids a double-splash
 * stutter.
 */
private const val SPLASH_HOLD_MILLIS = 600L

@Composable
fun InknautSplashScreen(
    isDarkTheme: Boolean,
    onComposed: () -> Unit,
    onFinished: () -> Unit,
) {
    val background = if (isDarkTheme) Color(0xFF01172E) else Color.White
    val lockup = if (isDarkTheme) R.drawable.inknaut_lockup_stack_dark else R.drawable.inknaut_lockup_stack_light

    Surface(color = background, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = lockup),
                contentDescription = "Inknaut Labs",
                modifier = Modifier.size(160.dp),
            )
        }
    }

    LaunchedEffect(Unit) {
        // Signals the platform splash screen that Compose has actually drawn a frame in the
        // matching background color, so the native overlay is never removed a frame early.
        onComposed()
        delay(SPLASH_HOLD_MILLIS)
        onFinished()
    }
}
