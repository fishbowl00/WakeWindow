package com.wakewindow.app.domain.settings

import com.wakewindow.app.domain.model.UnitSystem

/**
 * Single-row app-wide settings, mirroring RideCast's own singleton-config-entity pattern
 * (see docs/RIDECAST_REFERENCE_AUDIT.md section 2) - WakeWindow is single-user, so one
 * settings record is enough; per-launch/per-vessel data lives in its own saved records
 * instead, not here.
 */
data class AppSettings(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL_MARINE,
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val defaultOutingDurationMinutes: Int = 8 * 60,
)
