package com.wakewindow.app

import android.content.Context
import com.wakewindow.app.data.local.VesselPreferenceStore
import com.wakewindow.app.data.local.WakeWindowDatabase
import com.wakewindow.app.data.place.CompositeMarinePlaceProvider
import com.wakewindow.app.data.remote.coops.CoopsCurrentProvider
import com.wakewindow.app.data.remote.coops.CoopsTideProvider
import com.wakewindow.app.data.remote.fwc.FwcBoatRampProvider
import com.wakewindow.app.data.remote.ndbc.NdbcObservationProvider
import com.wakewindow.app.data.remote.nws.NwsProviders
import com.wakewindow.app.data.remote.openmeteo.OpenMeteoGeneralProvider
import com.wakewindow.app.data.remote.openmeteo.OpenMeteoMarineProvider
import com.wakewindow.app.data.remote.photon.PhotonPlaceProvider
import com.wakewindow.app.data.remote.usace.UsaceRecreationProvider
import com.wakewindow.app.data.repository.DefaultBoatingRepository
import com.wakewindow.app.data.repository.RoomSavedLaunchRepository
import com.wakewindow.app.domain.observation.MarineObservationProvider
import com.wakewindow.app.domain.place.MarinePlaceProvider
import com.wakewindow.app.domain.place.SavedLaunchRepository
import com.wakewindow.app.domain.route.BoatingRepository

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

    fun database(context: Context): WakeWindowDatabase =
        database ?: WakeWindowDatabase.build(context).also { database = it }

    fun savedLaunchRepository(context: Context): SavedLaunchRepository =
        RoomSavedLaunchRepository(database(context).savedLaunchDao())

    private var vesselPreferenceStore: VesselPreferenceStore? = null

    fun vesselPreferenceStore(context: Context): VesselPreferenceStore =
        vesselPreferenceStore ?: VesselPreferenceStore(context).also { vesselPreferenceStore = it }

    /**
     * Boating-specific, government-sourced discovery (Florida FWC boat ramps, USACE
     * reservoir recreation areas) is fanned out ahead of general-purpose keyless geocoding
     * (Photon), which is kept only as the broad-coverage fallback - see
     * docs/PLACE_DISCOVERY.md.
     */
    fun placeProvider(): MarinePlaceProvider = CompositeMarinePlaceProvider(
        boatingSources = listOf(FwcBoatRampProvider(), UsaceRecreationProvider()),
        fallback = PhotonPlaceProvider(),
    )

    fun observationProvider(): MarineObservationProvider = NdbcObservationProvider()

    /**
     * NWS is the primary, production-safe source for both general and marine forecast data
     * (see docs/DATA_SOURCES.md). Open-Meteo is included as a second concurrent source purely
     * to improve consensus/confidence during development - see the commercial-licensing note
     * in docs/DATA_SOURCES.md before shipping this configuration to production. NDBC buoy
     * observations are public/open government data with no such constraint - see
     * docs/DATA_SOURCES.md "Provider licensing summary."
     */
    fun boatingRepository(): BoatingRepository = DefaultBoatingRepository(
        generalProviders = listOf(nwsProviders, OpenMeteoGeneralProvider()),
        marineForecastProviders = listOf(nwsProviders, OpenMeteoMarineProvider()),
        alertProvider = nwsProviders,
        tideProvider = CoopsTideProvider(),
        observationProvider = observationProvider(),
        pointTypeProvider = nwsProviders,
        currentProvider = CoopsCurrentProvider(),
    )
}
