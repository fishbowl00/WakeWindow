package com.wakewindow.app.data.cache

import com.wakewindow.app.data.local.CacheDao
import com.wakewindow.app.data.local.CacheEntryEntity
import java.time.Instant

class RoomCacheStore(private val dao: CacheDao) : CacheStore {

    override suspend fun get(key: String): CacheRecord? =
        dao.get(key)?.let {
            CacheRecord(
                key = it.key,
                payload = it.payload,
                fetchedAt = Instant.ofEpochMilli(it.fetchedAtEpochMillis),
                expiresAt = Instant.ofEpochMilli(it.expiresAtEpochMillis),
            )
        }

    override suspend fun put(record: CacheRecord) {
        dao.upsert(
            CacheEntryEntity(
                key = record.key,
                payload = record.payload,
                fetchedAtEpochMillis = record.fetchedAt.toEpochMilli(),
                expiresAtEpochMillis = record.expiresAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun delete(key: String) = dao.delete(key)

    override suspend fun deleteExpired(now: Instant) = dao.deleteExpired(now.toEpochMilli())
}
