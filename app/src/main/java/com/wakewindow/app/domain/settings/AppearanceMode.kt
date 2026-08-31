package com.wakewindow.app.domain.settings

/** Persisted (not just OS) appearance preference. Kept in `domain` - pure enum, no Compose
 * dependency - with its Compose-aware resolution as an extension function in `ui/theme`. */
enum class AppearanceMode {
    LIGHT,
    DARK,
    SYSTEM,
}
