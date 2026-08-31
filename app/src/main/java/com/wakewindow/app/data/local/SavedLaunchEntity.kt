package com.wakewindow.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Persisted saved-launch record. Only discovery-level fields (name/coordinates/address/type)
 * are stored - facility-intelligence fields (§ docs/PRODUCT.md "Marine place / launch
 * intelligence") have no verified source this sprint, so they are simply absent rather than
 * persisted as null placeholders. No [androidx.room.TypeConverter]s: epoch millis for the
 * timestamp, the enum's plain name for type, matching RideCast's own convention (see
 * docs/RIDECAST_REFERENCE_AUDIT.md section 1) so an unknown future value never fails to load.
 */
@Entity(tableName = "saved_launch")
data class SavedLaunchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val placeType: String,
    val isFavorite: Boolean,
    val savedAtEpochMillis: Long,
    /** See [com.wakewindow.app.domain.place.SavedLaunch] "Recent plans" - null columns for a
     * launch saved before this field existed, or one never actually planned yet. */
    val lastDepartureHourOfDay: Int? = null,
    val lastDurationMinutes: Long? = null,
    val lastVesselProfileId: String? = null,
)

@Dao
interface SavedLaunchDao {
    @Query("SELECT * FROM saved_launch ORDER BY savedAtEpochMillis DESC")
    suspend fun getAll(): List<SavedLaunchEntity>

    @Upsert
    suspend fun upsert(entity: SavedLaunchEntity)

    @Query("DELETE FROM saved_launch WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE saved_launch SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)
}
