package com.wakewindow.app.data.repository

import com.wakewindow.app.data.local.SavedLaunchDao
import com.wakewindow.app.data.mapper.SavedLaunchMapper
import com.wakewindow.app.domain.place.SavedLaunch
import com.wakewindow.app.domain.place.SavedLaunchRepository

class RoomSavedLaunchRepository(
    private val dao: SavedLaunchDao,
) : SavedLaunchRepository {

    override suspend fun getAll(): List<SavedLaunch> =
        dao.getAll().map(SavedLaunchMapper::toDomain)

    override suspend fun save(launch: SavedLaunch) {
        dao.upsert(SavedLaunchMapper.toEntity(launch))
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        dao.setFavorite(id, isFavorite)
    }
}
