package com.wakewindow.app

import android.content.Context
import com.wakewindow.app.data.cache.CachedCurrentProvider
import com.wakewindow.app.data.cache.CachedGeneralWeatherProvider
import com.wakewindow.app.data.cache.CachedMarineAlertProvider
import com.wakewindow.app.data.cache.CachedMarineForecastProvider
import com.wakewindow.app.data.cache.CachedTideProvider
import com.wakewindow.app.data.cache.DurableCache
import com.wakewindow.app.data.cache.RoomCacheStore
import com.wakewindow.app.data.local.VesselPreferenceStore
import com.wakewindow.app.data.local.WakeWindowDatabase
import com.wakewindow.app.data.place.CachedMarinePlaceProvider
import com.wakewindow.app.data.place.CompositeMarinePlaceProvider
import com.wakewindow.app.data.remote.coops.CoopsCurrentProvider
import com.wakewindow.app.data.remote.coops.CoopsTideProvider
import com.wakewindow.app.data.remote.fwc.FwcBoatRampProvider
import com.wakewindow.app.data.remote.fwc.FwcFacilityInfoProvider
import com.wakewindow.app.data.remote.ndbc.NdbcObservationProvider
import com.wakewindow.app.data.remote.nws.NwsProviders
import com.wakewindow.app.data.remote.openmeteo.OpenMeteoGeneralProvider
import com.wakewindow.app.data.remote.openmeteo.OpenMeteoMarineProvider
import com.wakewindow.app.data.remote.photon.PhotonPlaceProvider
import com.wakewindow.app.data.remote.usace.UsaceRecreationProvider
import com.wakewindow.app.data.repository.DefaultBoatingRepository
import com.wakewindow.app.data.repository.DefaultTripBoatingRepository
import com.wakewindow.app.data.repository.RoomSavedLaunchRepository
import com.wakewindow.app.data.repository.RoomSavedTripRepository
import com.wakewindow.app.data.repository.RoomVesselProfileRepository
import com.wakewindow.app.domain.alert.MarineAlertProvider
import com.wakewindow.app.domain.marine.MarineForecastProvider
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.place.MarineFacilityInfoProvider
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.SavedLaunchRepository
import com.wakewindow.app.domain.route.BoatingRepository
import com.wakewindow.app.domain.tide.CurrentProvider
import com.wakewindow.app.domain.tide.TideProvider
import com.wakewindow.app.domain.trip.SavedTripRepository
import com.wakewindow.app.domain.trip.TripBoatingRepository
import com.wakewindow.app.domain.vessel.VesselProfileRepository
import com.wakewindow.app.domain.weather.GeneralWeatherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency-injection composition root - no DI framework, matching RideCast's own
 * `AppDependencies` (see docs/RIDECAST_REFERENCE_AUDIT.md section 1/3). Screens that share a
 * repository must be handed the *same* instance from here, not construct their own - a
 * caching decorator (if/when one is added around [boatingRepository]) would otherwise be
 * silently defeated by duplicate instances.
 */
object AppDependencies {

    private var database: WakeWindowDatabase? = null

    // Shared so its grid-URL/time-zone-per-coordinate cache is actually shared across screens
    // rather than silently rebuilt per call - see the class doc above.
    val nwsProviders: NwsProviders by lazy { NwsProviders() }

    /** Process-lifetime scope backing [com.wakewindow.app.data.cache.RequestCoalescer] usage -
     * a coalesced request must outlive the single screen/ViewModel call that happened to start
     * it, since a *second* concurrent caller (a different screen, a fast back-then-forward
     * navigation) may await the same in-flight result. `SupervisorJob` so one failed request
     * never cancels this scope for the next one. */
    private val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    fun database(context: Context): WakeWindowDatabase =
        database ?: WakeWindowDatabase.build(context).also { database = it }

    private var durableCache: DurableCache? = null

    /** See docs/CACHE_POLICY.md for the TTL rationale behind each cache key prefix that uses
     * this. One shared instance so every caller's cached data lives in the same durable store. */
    fun durableCache(context: Context): DurableCache =
        durableCache ?: DurableCache(RoomCacheStore(database(context).cacheDao())).also { durableCache = it }

    fun savedLaunchRepository(context: Context): SavedLaunchRepository =
        RoomSavedLaunchRepository(database(context).savedLaunchDao())

    fun vesselProfileRepository(context: Context): VesselProfileRepository =
        RoomVesselProfileRepository(database(context).vesselProfileDao())

    private var vesselPreferenceStore: VesselPreferenceStore? = null

    fun vesselPreferenceStore(context: Context): VesselPreferenceStore =
        vesselPreferenceStore ?: VesselPreferenceStore(context).also { vesselPreferenceStore = it }

    /**
     * Boating-specific, government-sourced discovery (Florida FWC boat ramps, USACE
     * reservoir recreation areas) is fanned out ahead of general-purpose keyless geocoding
     * (Photon), which is kept only as the broad-coverage fallback - see
     * docs/PLACE_DISCOVERY.md.
     */
    fun placeProvider(context: Context): MarinePlaceProvider = CachedMarinePlaceProvider(
        delegate = CompositeMarinePlaceProvider(
            boatingSources = listOf(FwcBoatRampProvider(), UsaceRecreationProvider()),
            fallback = PhotonPlaceProvider(),
        ),
        cache = durableCache(context),
        scope = applicationScope,
    )

    fun observationProvider(): MarineObservationProvider = NdbcObservationProvider()

    /**
     * The first real [MarineFacilityInfoProvider] - Florida FWC boat ramp records only (see
     * docs/DATA_SOURCES.md "Marine place / launch intelligence"). A place from any other
     * source honestly reports [com.wakewindow.app.domain.place.FacilityInfoOutcome.NoDataAvailable]
     * rather than a guess.
     */
    fun facilityInfoProvider(context: Context): MarineFacilityInfoProvider =
        FwcFacilityInfoProvider(cache = durableCache(context))

    // Shared for the same reason as nwsProviders above - CoopsTideProvider/CoopsCurrentProvider
    // each hold their own process-lifetime in-memory station-list cache, which a fresh instance
    // per call would silently defeat. See docs/CACHE_POLICY.md "CO-OPS station metadata."
    private val coopsTideProvider: CoopsTideProvider by lazy { CoopsTideProvider() }
    private val coopsCurrentProvider: CoopsCurrentProvider by lazy { CoopsCurrentProvider() }

    /**
     * NWS is the primary, production-safe source for both general and marine forecast data
     * (see docs/DATA_SOURCES.md). Open-Meteo is included as a second concurrent source purely
     * to improve consensus/confidence during development - see the commercial-licensing note
     * in docs/DATA_SOURCES.md before shipping this configuration to production. NDBC buoy
     * observations are public/open government data with no such constraint - see
     * docs/DATA_SOURCES.md "Provider licensing summary."
     *
     * Every provider except NDBC observations is wrapped in [DurableCache] - see
     * docs/CACHE_POLICY.md for the full TTL table and the reasoning behind leaving NDBC
     * observations on their existing narrower in-memory cache instead. Shared by both
     * [boatingRepository] (Mode A) and [tripBoatingRepository] (Mode B), so a cache warmed by
     * one benefits the other - a day-outing plan and a trip plan through the same launch hit
     * the same cached grid/alert/tide data instead of each fetching independently.
     */
    private fun cachedGeneralProviders(context: Context, includeOpenMeteo: Boolean): List<GeneralWeatherProvider> = listOfNotNull(
        CachedGeneralWeatherProvider(nwsProviders, durableCache(context), applicationScope),
        OpenMeteoGeneralProvider().takeIf { includeOpenMeteo }?.let { CachedGeneralWeatherProvider(it, durableCache(context), applicationScope) },
    )

    private fun cachedMarineProviders(context: Context, includeOpenMeteo: Boolean): List<MarineForecastProvider> = listOfNotNull(
        CachedMarineForecastProvider(nwsProviders, durableCache(context), applicationScope),
        OpenMeteoMarineProvider().takeIf { includeOpenMeteo }?.let { CachedMarineForecastProvider(it, durableCache(context), applicationScope) },
    )

    private fun cachedAlertProvider(context: Context): MarineAlertProvider =
        CachedMarineAlertProvider(nwsProviders, durableCache(context), applicationScope)

    private fun cachedTideProvider(context: Context): TideProvider =
        CachedTideProvider(coopsTideProvider, durableCache(context), applicationScope)

    private fun cachedCurrentProvider(context: Context): CurrentProvider =
        CachedCurrentProvider(coopsCurrentProvider, durableCache(context), applicationScope)

    /** [includeOpenMeteo] defaults to true (today's development configuration) but WakeWindow
     * must remain fully functional with it false - see docs/DATA_SOURCES.md "Remove structural
     * Open-Meteo dependency." `DefaultBoatingRepositoryTest` exercises the resulting
     * single-general-provider/single-marine-provider shape directly against hand-written fakes
     * (never through this cached wiring, which is exercised by the `Cached*ProviderTest` suite
     * instead) - this parameter is the seam that lets a real build actually opt into that
     * configuration. */
    fun boatingRepository(context: Context, includeOpenMeteo: Boolean = true): BoatingRepository = DefaultBoatingRepository(
        generalProviders = cachedGeneralProviders(context, includeOpenMeteo),
        marineForecastProviders = cachedMarineProviders(context, includeOpenMeteo),
        alertProvider = cachedAlertProvider(context),
        tideProvider = cachedTideProvider(context),
        observationProvider = observationProvider(),
        pointTypeProvider = nwsProviders,
        currentProvider = cachedCurrentProvider(context),
    )

    /** The Mode B (trip) counterpart to [boatingRepository] - see
     * [com.wakewindow.app.data.repository.DefaultTripBoatingRepository]. Reuses the exact same
     * cached provider instances as [boatingRepository], not a second independent set - see
     * this object's own class doc on why shared instances matter for caching. */
    fun tripBoatingRepository(context: Context, includeOpenMeteo: Boolean = true): TripBoatingRepository = DefaultTripBoatingRepository(
        generalProviders = cachedGeneralProviders(context, includeOpenMeteo),
        marineForecastProviders = cachedMarineProviders(context, includeOpenMeteo),
        alertProvider = cachedAlertProvider(context),
        tideProvider = cachedTideProvider(context),
        observationProvider = observationProvider(),
        pointTypeProvider = nwsProviders,
        currentProvider = cachedCurrentProvider(context),
    )

    fun savedTripRepository(context: Context): SavedTripRepository =
        RoomSavedTripRepository(database(context).savedTripDao())
}
