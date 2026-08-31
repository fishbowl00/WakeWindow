package com.wakewindow.app.domain.vessel

/**
 * Persistence for user-created/edited vessel profiles, distinct from the five built-in
 * [VesselProfile.presets] - see docs/VESSEL_PROFILES.md. Presets are never stored here; they
 * are a compile-time constant list. Architected for multiple saved profiles from the start
 * (each with its own [VesselProfile.id]) rather than a single global custom profile, even
 * though the first UI may only expose editing one at a time.
 */
interface VesselProfileRepository {
    suspend fun getAllCustom(): List<VesselProfile>
    suspend fun save(profile: VesselProfile)
    suspend fun delete(id: String)
}
