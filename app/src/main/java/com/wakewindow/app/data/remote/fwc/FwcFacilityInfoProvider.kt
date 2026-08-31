package com.wakewindow.app.data.remote.fwc

import com.wakewindow.app.data.cache.DurableCache
import com.wakewindow.app.domain.place.FacilityInfoOutcome
import com.wakewindow.app.domain.place.MarineFacilityInfoProvider
import com.wakewindow.app.domain.place.MarinePlaceCandidate
import com.wakewindow.app.domain.place.PlaceSourceType
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.time.Duration

/**
 * The first real [MarineFacilityInfoProvider] implementation - see docs/DATA_SOURCES.md
 * "Marine place / launch intelligence" and docs/ROADMAP.md "Launch intelligence." Only ever
 * claims to have data for a place that actually came from [PlaceSourceType.FWC_BOAT_RAMP] -
 * anything else honestly reports [FacilityInfoOutcome.NoDataAvailable] rather than guessing.
 *
 * Re-fetches the exact source record by [MarinePlaceCandidate.sourceId] (FWC's ArcGIS
 * `OBJECTID`) when available, since that's an exact, stable match; falls back to an exact
 * ramp-name match for a candidate saved before `sourceId` was tracked.
 *
 * When [cache] is supplied, a successful lookup is cached for [FACILITY_TTL] - see
 * docs/CACHE_POLICY.md ("FWC ramp metadata" - days, since ramp facility details change on
 * roughly the timescale of a physical renovation, not minute to minute). Only a genuinely
 * successful record is ever cached; a transient failure or a real "no data" result is never
 * remembered as if it were a durable fact.
 */
class FwcFacilityInfoProvider(
    private val service: FwcService = defaultService(),
    private val cache: DurableCache? = null,
) : MarineFacilityInfoProvider {

    override suspend fun facilityInfoFor(place: MarinePlaceCandidate): FacilityInfoOutcome {
        if (place.sourceType != PlaceSourceType.FWC_BOAT_RAMP) return FacilityInfoOutcome.NoDataAvailable
        val activeCache = cache ?: return fetchFresh(place)

        val cacheKey = "fwc_facility:" + (place.sourceId ?: place.name.uppercase())
        return try {
            val facility = activeCache.getOrFetch(
                key = cacheKey,
                ttl = FACILITY_TTL,
                serialize = FwcFacilityCacheCodec::encode,
                deserialize = FwcFacilityCacheCodec::decode,
            ) {
                when (val outcome = fetchFresh(place)) {
                    is FacilityInfoOutcome.Success -> outcome.facility
                    FacilityInfoOutcome.NoDataAvailable -> throw NoFacilityDataException()
                    is FacilityInfoOutcome.Failure -> throw FacilityFetchFailedException(outcome.message, outcome.cause)
                }
            }
            FacilityInfoOutcome.Success(facility)
        } catch (e: NoFacilityDataException) {
            FacilityInfoOutcome.NoDataAvailable
        } catch (e: FacilityFetchFailedException) {
            FacilityInfoOutcome.Failure(e.message ?: "FWC facility lookup failed", e.cause)
        }
    }

    private suspend fun fetchFresh(place: MarinePlaceCandidate): FacilityInfoOutcome {
        val where = place.sourceId?.let { FwcMapper.whereClauseForObjectId(it) }
            ?: FwcMapper.whereClauseForExactName(place.name)

        return try {
            val response = service.search(where = where, resultRecordCount = 1)
            val attributes = response.features.firstOrNull()?.attributes
                ?: return FacilityInfoOutcome.NoDataAvailable
            FacilityInfoOutcome.Success(FwcMapper.toFacilityInfo(attributes))
        } catch (e: HttpException) {
            FacilityInfoOutcome.Failure("FWC facility lookup failed (HTTP ${e.code()})", e)
        } catch (e: IOException) {
            FacilityInfoOutcome.Failure("Network error contacting FWC boat ramp inventory", e)
        }
    }

    /** Internal signaling only - never escapes [facilityInfoFor], which always converts these
     * back into the real [FacilityInfoOutcome] shape. */
    private class NoFacilityDataException : Exception()
    private class FacilityFetchFailedException(message: String, cause: Throwable?) : Exception(message, cause)

    companion object {
        private val FACILITY_TTL: Duration = Duration.ofDays(7)

        private fun defaultService(): FwcService {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val client = OkHttpClient.Builder().build()
            return Retrofit.Builder()
                .baseUrl("https://gis.myfwc.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FwcService::class.java)
        }
    }
}
