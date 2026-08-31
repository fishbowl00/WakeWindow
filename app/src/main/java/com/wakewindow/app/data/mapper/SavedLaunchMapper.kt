package com.wakewindow.app.data.mapper

import com.wakewindow.app.data.local.SavedLaunchEntity
import com.wakewindow.app.domain.model.GeoPoint
import com.wakewindow.app.domain.place.MarinePlace
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.MarinePlaceType
import com.wakewindow.app.domain.place.SavedLaunch

object SavedLaunchMapper {

    fun toDomain(entity: SavedLaunchEntity): SavedLaunch = SavedLaunch(
        id = entity.id,
        place = MarinePlace(
            id = entity.id,
            discovery = MarinePlaceCandidate(
                name = entity.name,
                location = GeoPoint(entity.latitude, entity.longitude),
                address = entity.address,
                guessedType = runCatching { MarinePlaceType.valueOf(entity.placeType) }.getOrDefault(MarinePlaceType.OTHER),
            ),
        ),
        isFavorite = entity.isFavorite,
        savedAtEpochMillis = entity.savedAtEpochMillis,
        lastDepartureHourOfDay = entity.lastDepartureHourOfDay,
        lastDurationMinutes = entity.lastDurationMinutes,
        lastVesselProfileId = entity.lastVesselProfileId,
    )

    fun toEntity(launch: SavedLaunch): SavedLaunchEntity = SavedLaunchEntity(
        id = launch.id,
        name = launch.place.name,
        latitude = launch.place.location.latitude,
        longitude = launch.place.location.longitude,
        address = launch.place.discovery.address,
        placeType = launch.place.type.name,
        isFavorite = launch.isFavorite,
        savedAtEpochMillis = launch.savedAtEpochMillis,
        lastDepartureHourOfDay = launch.lastDepartureHourOfDay,
        lastDurationMinutes = launch.lastDurationMinutes,
        lastVesselProfileId = launch.lastVesselProfileId,
    )
}
