package com.wakewindow.app.domain.alert

import com.wakewindow.app.domain.model.GeoPoint

sealed interface MarineAlertOutcome {
    data class Success(val alerts: List<MarineAlert>) : MarineAlertOutcome
    data class Failure(val message: String, val cause: Throwable? = null) : MarineAlertOutcome
}

/** Active marine watches/warnings/advisories for a point - NWS `/alerts/active?point=`. */
interface MarineAlertProvider {
    suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome
}
