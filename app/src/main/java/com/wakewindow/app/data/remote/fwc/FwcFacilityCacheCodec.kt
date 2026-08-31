package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.domain.model.SourceReference
import com.wakewindow.app.domain.model.SourceType
import com.wakewindow.app.domain.place.FacilityOperationalStatus
import com.wakewindow.app.domain.place.MarineFacilityInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Serializes just the fields FWC actually populates (see [FwcMapper.toFacilityInfo]) for
 * [com.wakewindow.app.data.cache.DurableCache] - every other [MarineFacilityInfo] field stays
 * at its default `UNKNOWN`/null either way, so a narrow cache DTO loses nothing real. Kept in
 * this data-layer package rather than annotating the domain type itself with
 * `kotlinx.serialization`, matching this codebase's existing DTO-vs-domain split (see
 * [FwcAttributes]).
 */
@Serializable
private data class FwcFacilityCacheDto(
    val phone: String? = null,
    val waterBodyName: String? = null,
    val rampLanes: Int? = null,
    val rampType: String? = null,
    val accessType: String? = null,
    val amenitiesRaw: String? = null,
    val operationalStatus: String = FacilityOperationalStatus.UNKNOWN.name,
    val operationalStatusRaw: String? = null,
    val sourceName: String,
    val sourceUrl: String? = null,
    val retrievedAtEpochMillis: Long,
    val recordId: String? = null,
)

object FwcFacilityCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(facility: MarineFacilityInfo): String {
        val source = requireNotNull(facility.source) { "only a facility record with a source is ever cached" }
        return json.encodeToString(
            FwcFacilityCacheDto.serializer(),
            FwcFacilityCacheDto(
                phone = facility.phone,
                waterBodyName = facility.waterBodyName,
                rampLanes = facility.rampLanes,
                rampType = facility.rampType,
                accessType = facility.accessType,
                amenitiesRaw = facility.amenitiesRaw,
                operationalStatus = facility.operationalStatus.name,
                operationalStatusRaw = facility.operationalStatusRaw,
                sourceName = source.sourceName,
                sourceUrl = source.sourceUrl,
                retrievedAtEpochMillis = source.retrievedAt.toEpochMilli(),
                recordId = source.recordId,
            ),
        )
    }

    fun decode(payload: String): MarineFacilityInfo {
        val dto = json.decodeFromString(FwcFacilityCacheDto.serializer(), payload)
        return MarineFacilityInfo(
            phone = dto.phone,
            waterBodyName = dto.waterBodyName,
            rampLanes = dto.rampLanes,
            rampType = dto.rampType,
            accessType = dto.accessType,
            amenitiesRaw = dto.amenitiesRaw,
            operationalStatus = runCatching { FacilityOperationalStatus.valueOf(dto.operationalStatus) }.getOrDefault(FacilityOperationalStatus.UNKNOWN),
            operationalStatusRaw = dto.operationalStatusRaw,
            source = SourceReference(
                sourceName = dto.sourceName,
                sourceUrl = dto.sourceUrl,
                retrievedAt = Instant.ofEpochMilli(dto.retrievedAtEpochMillis),
                recordId = dto.recordId,
                sourceType = SourceType.STATE_AGENCY,
                isOfficial = true,
            ),
        )
    }
}
