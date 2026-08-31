package com.wakewindow.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.wakewindow.app.domain.scoring.BoatingCategory

fun BoatingCategory.toColor(): Color = when (this) {
    BoatingCategory.EXCELLENT -> CategoryColors.Excellent
    BoatingCategory.GOOD -> CategoryColors.Good
    BoatingCategory.CAUTION -> CategoryColors.Caution
    BoatingCategory.POOR -> CategoryColors.Poor
    BoatingCategory.NO_GO -> CategoryColors.NoGo
    BoatingCategory.UNAVAILABLE -> CategoryColors.Unavailable
}

fun BoatingCategory.label(): String = when (this) {
    BoatingCategory.EXCELLENT -> "Excellent"
    BoatingCategory.GOOD -> "Good"
    BoatingCategory.CAUTION -> "Caution"
    BoatingCategory.POOR -> "Poor"
    BoatingCategory.NO_GO -> "No-Go"
    BoatingCategory.UNAVAILABLE -> "Unavailable"
}
