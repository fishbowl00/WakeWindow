package com.wakewindow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SavedLaunchEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WakeWindowDatabase : RoomDatabase() {
    abstract fun savedLaunchDao(): SavedLaunchDao

    companion object {
        const val DATABASE_NAME = "wakewindow.db"

        fun build(context: Context): WakeWindowDatabase =
            Room.databaseBuilder(context.applicationContext, WakeWindowDatabase::class.java, DATABASE_NAME).build()
    }
}
