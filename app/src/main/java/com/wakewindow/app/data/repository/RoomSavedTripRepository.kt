package com.wakewindow.app.data.repository

import com.wakewindow.app.data.local.SavedTripDao
import com.wakewindow.app.data.mapper.SavedTripMapper
import com.wakewindow.app.domain.trip.SavedTrip
import com.wakewindow.app.domain.trip.SavedTripRepository

class RoomSavedTripRepository(
    private val dao: SavedTripDao,
) : SavedTripRepository {

    override suspend fun getAll(): List<SavedTrip> =
        dao.getAll().map(SavedTripMapper::toDomain)

    override suspend fun save(trip: SavedTrip) {
        dao.upsert(SavedTripMapper.toEntity(trip))
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        dao.setFavorite(id, isFavorite)
    }
}
