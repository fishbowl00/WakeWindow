package com.wakewindow.app.data.mapper

import com.wakewindow.app.data.local.VesselProfileEntity
import com.wakewindow.app.domain.vessel.PropulsionType
import com.wakewindow.app.domain.vessel.VesselProfile
import com.wakewindow.app.domain.vessel.VesselType

object VesselProfileMapper {

    fun toDomain(entity: VesselProfileEntity): VesselProfile = VesselProfile(
        id = entity.id,
        name = entity.name,
        vesselType = runCatching { VesselType.valueOf(entity.vesselType) }.getOrDefault(VesselType.OTHER),
        lengthFt = entity.lengthFt,
        beamFt = entity.beamFt,
        draftFt = entity.draftFt,
        propulsionType = entity.propulsionType?.let { runCatching { PropulsionType.valueOf(it) }.getOrNull() },
        cruiseSpeedKts = entity.cruiseSpeedKts,
        windToleranceKts = entity.windToleranceKts,
        gustToleranceKts = entity.gustToleranceKts,
        waveToleranceFt = entity.waveToleranceFt,
        thunderstormTolerancePercent = entity.thunderstormTolerancePercent,
        visibilityToleranceNm = entity.visibilityToleranceNm,
        isSmallCraft = entity.isSmallCraft,
        isCustom = true,
        notes = entity.notes,
        createdAtEpochMillis = entity.createdAtEpochMillis,
        updatedAtEpochMillis = entity.updatedAtEpochMillis,
    )

    fun toEntity(profile: VesselProfile, nowEpochMillis: Long): VesselProfileEntity = VesselProfileEntity(
        id = profile.id,
        name = profile.name,
        vesselType = profile.vesselType.name,
        lengthFt = profile.lengthFt,
        beamFt = profile.beamFt,
        draftFt = profile.draftFt,
        propulsionType = profile.propulsionType?.name,
        cruiseSpeedKts = profile.cruiseSpeedKts,
        windToleranceKts = profile.windToleranceKts,
        gustToleranceKts = profile.gustToleranceKts,
        waveToleranceFt = profile.waveToleranceFt,
        thunderstormTolerancePercent = profile.thunderstormTolerancePercent,
        visibilityToleranceNm = profile.visibilityToleranceNm,
        isSmallCraft = profile.isSmallCraft,
        notes = profile.notes,
        createdAtEpochMillis = profile.createdAtEpochMillis ?: nowEpochMillis,
        updatedAtEpochMillis = nowEpochMillis,
    )
}
