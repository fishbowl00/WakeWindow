package com.wakewindow.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Persisted saved-trip record - the Mode B counterpart to [SavedLaunchEntity]. Waypoints are
 * stored as an opaque JSON string ([com.wakewindow.app.data.mapper.SavedTripMapper] owns the
 * codec) rather than a Room [androidx.room.TypeConverter] or a separate join table - this
 * codebase has no precedent for either, and a small ordered list embedded as JSON matches the
 * existing durable-cache convention (`FwcFacilityCacheCodec`, `PlaceSearchCacheCodec`) for
 * "opaque serialized blob in a text column" more closely than introducing a new pattern would.
 * No [androidx.room.TypeConverter]s otherwise, matching [SavedLaunchEntity]'s own convention.
 */
@Entity(tableName = "saved_trip")
data class SavedTripEntity(
    @PrimaryKey val id: String,
    val name: String,
    val departureName: String,
    val departureLatitude: Double,
    val departureLongitude: Double,
    val departureId: String,
    val destinationName: String,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val destinationId: String,
    /** JSON-encoded ordered list of intermediate [com.wakewindow.app.domain.trip.PlanningWaypoint]s -
     * never a manual arrival time (a saved trip is reused with a fresh departure time each run,
     * so a stale absolute timestamp from a previous session would be meaningless). */
    val waypointsJson: String,
    val vesselProfileId: String? = null,
    val cruiseSpeedKts: Double? = null,
    val notes: String? = null,
    val zoneId: String,
    val isFavorite: Boolean,
    val savedAtEpochMillis: Long,
    /** See [SavedLaunchEntity.lastDepartureHourOfDay] - the same "usually 7 AM" recall, for trips. */
    val lastDepartureHourOfDay: Int? = null,
)

@Dao
interface SavedTripDao {
    @Query("SELECT * FROM saved_trip ORDER BY savedAtEpochMillis DESC")
    suspend fun getAll(): List<SavedTripEntity>

    @Upsert
    suspend fun upsert(entity: SavedTripEntity)

    @Query("DELETE FROM saved_trip WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE saved_trip SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)
}
