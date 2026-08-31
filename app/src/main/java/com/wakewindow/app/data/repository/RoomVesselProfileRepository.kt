package com.wakewindow.app.data.repository

import com.wakewindow.app.data.local.VesselProfileDao
import com.wakewindow.app.data.mapper.VesselProfileMapper
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.domain.vessel.VesselProfileRepository

class RoomVesselProfileRepository(
    private val dao: VesselProfileDao,
) : VesselProfileRepository {

    override suspend fun getAllCustom(): List<VesselProfile> =
        dao.getAll().map(VesselProfileMapper::toDomain)

    override suspend fun save(profile: VesselProfile) {
        dao.upsert(VesselProfileMapper.toEntity(profile, System.currentTimeMillis()))
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }
}
