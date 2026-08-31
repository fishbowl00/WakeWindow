package com.wakewindow.app.domain.place

/** Local persistence for saved launches - see docs/ARCHITECTURE.md "Caching" and
 * docs/RIDECAST_REFERENCE_AUDIT.md section 1 for why this stays a small, purpose-built
 * interface rather than a generic CRUD abstraction. */
interface SavedLaunchRepository {
    suspend fun getAll(): List<SavedLaunch>
    suspend fun save(launch: SavedLaunch)
    suspend fun delete(id: String)
    suspend fun setFavorite(id: String, isFavorite: Boolean)
}
