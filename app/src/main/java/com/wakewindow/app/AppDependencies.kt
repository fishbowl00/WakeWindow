package com.wakewindow.app

import android.content.Context
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
import com.wakewindow.app.data.repository.RoomSavedLaunchRepository
import com.wakewindow.app.data.repository.RoomVesselProfileRepository
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.place.MarineFacilityInfoProvider
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.SavedLaunchRepository
import com.wakewindow.app.domain.route.BoatingRepository
import com.wakewindow.app.domain.vessel.VesselProfileRepository
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

    /**
     * NWS is the primary, production-safe source for both general and marine forecast data
     * (see docs/DATA_SOURCES.md). Open-Meteo is included as a second concurrent source purely
     * to improve consensus/confidence during development - see the commercial-licensing note
     * in docs/DATA_SOURCES.md before shipping this configuration to production. NDBC buoy
     * observations are public/open government data with no such constraint - see
     * docs/DATA_SOURCES.md "Provider licensing summary."
     *
     * [includeOpenMeteo] defaults to true (today's development configuration) but WakeWindow
     * must remain fully functional with it false - see docs/DATA_SOURCES.md "Remove structural
     * Open-Meteo dependency." `DefaultBoatingRepositoryTest` exercises the resulting
     * single-general-provider/single-marine-provider shape directly (every existing fake-based
     * test there already runs exactly one general and one marine provider, which is
     * structurally identical to this flag set to false); this parameter is the seam that lets
     * a real build actually opt into that configuration, not merely a config value nothing
     * reads.
     */
    fun boatingRepository(includeOpenMeteo: Boolean = true): BoatingRepository = DefaultBoatingRepository(
        generalProviders = listOfNotNull(nwsProviders, OpenMeteoGeneralProvider().takeIf { includeOpenMeteo }),
        marineForecastProviders = listOfNotNull(nwsProviders, OpenMeteoMarineProvider().takeIf { includeOpenMeteo }),
        alertProvider = nwsProviders,
        tideProvider = CoopsTideProvider(),
        observationProvider = observationProvider(),
        pointTypeProvider = nwsProviders,
        currentProvider = CoopsCurrentProvider(),
    )
}
