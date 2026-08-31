package com.wakewindow.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Durable backing store for [com.wakewindow.app.data.cache.DurableCache] - see
 * docs/CACHE_POLICY.md. [payload] is an opaque, caller-serialized string (usually JSON); this
 * table has no idea what it's caching, only when it expires. */
@Entity(tableName = "cache_entry")
data class CacheEntryEntity(
    @PrimaryKey val key: String,
    val payload: String,
    val fetchedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entry WHERE `key` = :key")
    suspend fun get(key: String): CacheEntryEntity?

    @Upsert
    suspend fun upsert(entry: CacheEntryEntity)

    @Query("DELETE FROM cache_entry WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM cache_entry WHERE expiresAtEpochMillis <= :nowEpochMillis")
    suspend fun deleteExpired(nowEpochMillis: Long)
}
