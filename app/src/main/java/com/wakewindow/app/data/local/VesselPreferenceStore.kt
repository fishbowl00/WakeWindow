package com.wakewindow.app.data.local

import android.content.Context
import com.wakewindow.app.domain.vessel.VesselProfile

/**
 * Persists which vessel profile - preset or user-saved custom - is currently active, as a
 * plain SharedPreferences scalar (an ID) rather than a Room column, matching
 * [com.wakewindow.app.domain.settings.AppSettings]'s own single-record rationale. See
 * docs/VESSEL_PROFILES.md. The full custom-profile records themselves live in Room
 * ([com.wakewindow.app.data.local.VesselProfileEntity]) - this store only remembers *which one*
 * (by [VesselProfile.id], which for a preset is simply its name) is selected.
 */
class VesselPreferenceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Null when nothing has been chosen yet - callers fall back to [VesselProfile.default]. */
    fun loadSelectedProfileId(): String? = prefs.getString(KEY_SELECTED_PROFILE_ID, null)

    fun saveSelectedProfileId(id: String) {
        prefs.edit().putString(KEY_SELECTED_PROFILE_ID, id).apply()
    }

    /** Convenience for the common case: resolve the previously-selected ID against the full
     * available set (presets + saved custom profiles), falling back to [VesselProfile.default]
     * if nothing was selected yet or the selected ID no longer matches anything (e.g. a custom
     * profile that was since deleted). */
    fun loadSelectedProfile(customProfiles: List<VesselProfile>): VesselProfile {
        val id = loadSelectedProfileId() ?: return VesselProfile.default()
        return (VesselProfile.presets() + customProfiles).firstOrNull { it.id == id } ?: VesselProfile.default()
    }

    companion object {
        private const val PREFS_NAME = "wakewindow_vessel_prefs"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    }
}
