package com.wakewindow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version bumped 1 -> 2 for [VesselProfileEntity] and [CacheEntryEntity] (see
 * docs/VESSEL_PROFILES.md and docs/CACHE_POLICY.md). No migration is written: this is
 * pre-release, local-only, single-user storage with no real installs to preserve yet (see
 * docs/ROADMAP.md's "no backend/no accounts" stance) - a destructive fallback that simply
 * re-creates the database is the honest, low-risk choice here rather than a migration for data
 * that doesn't exist. Once WakeWindow has real users, a version bump must ship a real
 * [androidx.room.migration.Migration] instead.
 */
@Database(
    entities = [SavedLaunchEntity::class, VesselProfileEntity::class, CacheEntryEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class WakeWindowDatabase : RoomDatabase() {
    abstract fun savedLaunchDao(): SavedLaunchDao
    abstract fun vesselProfileDao(): VesselProfileDao
    abstract fun cacheDao(): CacheDao

    companion object {
        const val DATABASE_NAME = "wakewindow.db"

        fun build(context: Context): WakeWindowDatabase =
            Room.databaseBuilder(context.applicationContext, WakeWindowDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
