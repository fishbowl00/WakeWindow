package com.wakewindow.app.data.cache

import com.wakewindow.app.data.remote.nws.NwsMapper
import com.wakewindow.app.domain.alert.MarineAlert
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Serializes only [MarineAlert]'s own source fields - [MarineAlert.severity] and
 * [MarineAlert.impact] are always re-derived from [MarineAlert.event] via
 * [NwsMapper.classify] on decode rather than serialized, so a future change to the
 * event->impact classification table takes effect immediately for anything already sitting in
 * cache, instead of a stale classification surviving until the entry naturally expires.
 */
@Serializable
private data class AlertDto(
    val id: String,
    val event: String,
    val headline: String? = null,
    val effectiveEpochMillis: Long? = null,
    val expiresEpochMillis: Long? = null,
    val areaDescription: String? = null,
)

object MarineAlertCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(alerts: List<MarineAlert>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AlertDto.serializer()),
            alerts.map { a ->
                AlertDto(
                    id = a.id,
                    event = a.event,
                    headline = a.headline,
                    effectiveEpochMillis = a.effective?.toEpochMilli(),
                    expiresEpochMillis = a.expires?.toEpochMilli(),
                    areaDescription = a.areaDescription,
                )
            },
        )

    fun decode(payload: String): List<MarineAlert> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AlertDto.serializer()), payload).map { dto ->
            val classification = NwsMapper.classify(dto.event)
            MarineAlert(
                id = dto.id,
                event = dto.event,
                headline = dto.headline,
                severity = classification.severity,
                effective = dto.effectiveEpochMillis?.let { Instant.ofEpochMilli(it) },
                expires = dto.expiresEpochMillis?.let { Instant.ofEpochMilli(it) },
                areaDescription = dto.areaDescription,
                impact = classification.impact,
            )
        }
}
