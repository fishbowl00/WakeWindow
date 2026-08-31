package com.wakewindow.app.data.local

import android.content.Context
import com.wakewindow.app.domain.vessel.VesselProfile

/**
 * Persists which vessel preset the user last picked - a plain SharedPreferences value rather
 * than a new Room entity/migration, since it's a single scalar (a preset name) for a
 * single-user app, matching [com.wakewindow.app.domain.settings.AppSettings]'s own
 * single-record rationale. See docs/ROADMAP.md "Vessel profiles."
 */
class VesselPreferenceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Null when nothing has been chosen yet, or the stored name no longer matches a known
     * preset (e.g. after a preset list change) - callers fall back to [VesselProfile.default]. */
    fun loadSelectedPreset(): VesselProfile? {
        val name = prefs.getString(KEY_SELECTED_PRESET_NAME, null) ?: return null
        return VesselProfile.presets().firstOrNull { it.name == name }
    }

    fun saveSelectedPreset(profile: VesselProfile) {
        prefs.edit().putString(KEY_SELECTED_PRESET_NAME, profile.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "wakewindow_vessel_prefs"
        private const val KEY_SELECTED_PRESET_NAME = "selected_preset_name"
    }
}
