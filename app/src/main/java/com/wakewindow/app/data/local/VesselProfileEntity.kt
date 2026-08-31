package com.wakewindow.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * A user-created or user-edited vessel profile - see docs/VESSEL_PROFILES.md. The five built-in
 * presets ([com.wakewindow.app.domain.vessel.VesselProfile.presets]) are never stored here;
 * only a profile the user actually saved via the vessel profile screen gets a row. No
 * [androidx.room.TypeConverter]s, matching [SavedLaunchEntity]'s own convention: enum values
 * are plain strings so an unrecognized future value never fails to load, just falls back.
 */
@Entity(tableName = "vessel_profile")
data class VesselProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val vesselType: String,
    val lengthFt: Double?,
    val beamFt: Double?,
    val draftFt: Double?,
    val propulsionType: String?,
    val cruiseSpeedKts: Double?,
    val windToleranceKts: Double,
    val gustToleranceKts: Double,
    val waveToleranceFt: Double,
    val thunderstormTolerancePercent: Int,
    val visibilityToleranceNm: Double,
    val isSmallCraft: Boolean,
    val notes: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Dao
interface VesselProfileDao {
    @Query("SELECT * FROM vessel_profile ORDER BY updatedAtEpochMillis DESC")
    suspend fun getAll(): List<VesselProfileEntity>

    @Upsert
    suspend fun upsert(entity: VesselProfileEntity)

    @Query("DELETE FROM vessel_profile WHERE id = :id")
    suspend fun delete(id: String)
}
