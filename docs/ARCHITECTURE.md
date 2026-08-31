# WakeWindow — Architecture

This is the concrete architecture the app is actually built with. See
[RIDECAST_REFERENCE_AUDIT.md](RIDECAST_REFERENCE_AUDIT.md) for the reasoning behind each
choice relative to RideCast.

## Platform & toolchain

Single-module native Android app (`:app`), Kotlin, Jetpack Compose, no backend, no user
accounts, local-first persistence (Room). No KMP module split this sprint — see the audit's
§4 "explicitly not carried into WakeWindow" for why, and the note below on how the domain
layer is kept ready for that split later without a rewrite.

Pinned versions (`gradle/libs.versions.toml`), chosen to match a combination proven to build
on this exact machine's SDK install (`compileSdk 35`, `build-tools 34.0.0`/`36.0.0`) rather
than the newest available majors:

| | Version |
|---|---|
| Gradle | 8.7 |
| AGP | 8.5.2 |
| Kotlin | 2.0.20 |
| KSP | 2.0.20-1.0.25 |
| Compose BOM | 2024.09.00 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 |
| kotlinx.serialization | 1.7.1 |
| kotlinx.coroutines | 1.8.1 |
| Room | 2.6.1 |
| Navigation Compose | 2.8.0 |

## Package layout

```
com.wakewindow.app
├── domain/                     — pure Kotlin. No android.*/androidx.* imports, ever.
│   ├── model/                  — GeoPoint, SourceReference (+ SourceType), Confidence, Units
│   ├── marine/                 — MarineConditions, MarineForecastProvider
│   ├── weather/                — GeneralWeatherProvider, ForecastOutcome (shared by general + marine fetches)
│   ├── tide/                   — TideProvider, CurrentProvider, TideEvent/TideTrend/CurrentEvent,
│   │                              TideTimeline/CurrentTimeline, station models
│   ├── alert/                  — MarineAlertProvider, MarineAlert, AlertTiming,
│   │                              MarineAlertImpact (+ AlertImpactCategory/Behavior, AlertSeverityCap)
│   ├── observation/             — MarineObservationProvider, SelectedMarineStation,
│   │                              ObservationFreshness, MarineDisagreement(Detector),
│   │                              WaterEnvironment(Classifier), WaterPointTypeProvider,
│   │                              StationRepresentativeness(Evaluator), ObservationForecastComparison
│   ├── place/                  — MarinePlace, MarineFacilityInfo (+ FacilityAvailability),
│   │                              MarinePlaceProvider, PlaceSourceType,
│   │                              MarineFacilityInfoProvider (no impl yet), SavedLaunch(Repository)
│   ├── vessel/                 — VesselProfile, VesselType, presets
│   ├── route/                  — RouteSample, RouteSampleRole, BoatingPlan, BoatingRepository
│   ├── consensus/               — multi-provider merge for MarineConditions
│   ├── scoring/                 — BoatingCategory, Hazard, PointAssessment,
│   │                              BoatingWindowAssessment, BestWindow, ConfidenceEvidence,
│   │                              MarineScoreEngine, MarinePointScorer, BestWindowFinder,
│   │                              ObservationalCautionEvaluator, EvidenceRequirementEvaluator
│   └── settings/                — AppSettings, AppearanceMode, unit preference
├── data/
│   ├── remote/
│   │   ├── nws/                 — points → grid → forecastGridData (general + marine + alerts, one fetch)
│   │   ├── openmeteo/           — general + marine (dev-only, license-gated - see DATA_SOURCES.md)
│   │   ├── coops/                — NOAA CO-OPS tide + current predictions, station metadata
│   │   ├── ndbc/                 — NOAA NDBC buoy observations: parser, station selector/directory, provider
│   │   ├── fwc/                   — Florida FWC boat ramp inventory (place discovery)
│   │   ├── usace/                 — USACE recreation-area parcels (place discovery)
│   │   └── photon/               — keyless place search (fallback)
│   ├── place/                    — CompositeMarinePlaceProvider (fan-out + rank + dedup across sources)
│   ├── local/                    — Room: SavedLaunchEntity; SharedPreferences: VesselPreferenceStore
│   ├── mapper/                   — DTO → domain, one file per provider
│   └── repository/               — DefaultBoatingRepository (provider fan-out + consensus + station-local
│                                    forecast/observation comparison), RoomSavedLaunchRepository
├── ui/
│   ├── theme/                    — WakeWindowTheme, AppearanceMode resolution, CategoryColors
│   ├── splash/                   — InknautSplashScreen
│   ├── navigation/                — WakeWindowNavHost, route constants
│   ├── launchlist/                 — saved launches, entry point
│   ├── launchsearch/                — place search (Photon-backed)
│   ├── planboat/                  — date + departure + return time selection, duration
│   ├── assessment/                 — full boating-day assessment display
│   ├── launchinfo/                 — MarineFacilityInfo display (honest-when-sparse)
│   ├── settings/
│   └── about/                     — safety disclaimer + Inknaut attribution
├── AppDependencies.kt              — manual DI composition root (object, RideCast-style)
├── WakeWindowViewModel.kt          — single shared ViewModel for the whole flow (see below)
└── MainActivity.kt
```

There is no separate `ui/components/` package yet — shared composables (metric columns, info
rows, category badges) currently live as private functions inside the screen that uses them
most; if a second screen needs the same one, promoting it to a shared file is a mechanical
follow-up, not a structural change.

`domain/` having zero Android/Compose/Retrofit/Room imports is enforced by convention (not a
lint rule yet — worth adding once the package is stable) and is what keeps a future `:shared`
KMP module split a mechanical move-and-recompile rather than a rewrite, exactly as RideCast's
own `shared` module does today.

## Dependency injection

No framework. `AppDependencies` (top-level object, mirroring RideCast's own) exposes factory
functions (`observationProvider()`, `database(context)`, `boatingRepository()`,
`savedLaunchRepository(context)`, `placeProvider()`) called from `MainActivity` and the shared
`WakeWindowViewModel`. `nwsProviders` is held as a `by lazy` singleton specifically so its
grid-URL/time-zone-per-coordinate cache is actually shared across every screen that needs NWS
data, rather than silently rebuilt per call — the same "same instance or the cache is
defeated" lesson RideCast's own `AppDependencies` documents.

## Provider interfaces (domain layer, no implementation detail leaks through)

Both general and marine forecast fetches return the same `ForecastOutcome` shape (a series of
`MarineConditions`, one per hour) — see `domain/weather/ForecastOutcome.kt` — since "fetch an
hourly series, maybe there's no coverage, maybe the call failed" is genuinely the same shape
for both concerns, not an accidentally-reused generic wrapper.

```kotlin
interface GeneralWeatherProvider {
    suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome
}

interface MarineForecastProvider {
    suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): ForecastOutcome
}

interface MarineObservationProvider {
    suspend fun nearestObservation(location: GeoPoint): MarineObservationOutcome
}

interface TideProvider {
    suspend fun nearestStation(location: GeoPoint): TideStationOutcome
    suspend fun events(stationId: String, date: LocalDate): TideEventsOutcome
}

interface CurrentProvider {
    suspend fun nearestStation(location: GeoPoint): CurrentStationOutcome
    suspend fun events(stationId: String, date: LocalDate): CurrentEventsOutcome
}

interface MarineAlertProvider {
    suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome
}

interface MarinePlaceProvider {
    suspend fun search(query: String, bias: GeoPoint?): PlaceSearchOutcome
}
```

Each `*Outcome` is its own small sealed type (`Success`/specific-empty-case/`Failure`), per
the audit's "no generic `Result<T>`" finding — e.g. `TideStationOutcome` distinguishes
"no tide station near this water" from "the CO-OPS API call failed," which a nullable or a
generic `Result` would conflate and a `BoatingWindowAssessment` needs to tell apart (the first
is a real, permanent fact about an inland lake; the second is a transient provider failure).

Concrete implementations (`data/remote/nws/NwsMarineForecastProvider`, etc.) never leak an SDK
type (a Retrofit `Response`, a DTO) past the mapper — only domain types cross the
`data`/`domain` boundary.

## Data model spine

```kotlin
data class MarineConditions(
    val timestamp: Instant,
    val location: GeoPoint,
    val sustainedWindKts: Double?,
    val windDirectionDeg: Double?,
    val gustKts: Double?,
    val precipitationProbabilityPercent: Int?,
    val thunderstormProbabilityPercent: Int?,
    val visibilityNm: Double?,
    val airTemperatureF: Double?,
    val waterTemperatureF: Double?,
    val waveHeightFt: Double?,
    val waveDirectionDeg: Double?,
    val wavePeriodSec: Double?,
    val swellHeightFt: Double?,
    val swellDirectionDeg: Double?,
    val swellPeriodSec: Double?,
    val tideHeightFt: Double?,
    val tideTrend: TideTrend?,
    val nextHighTide: TideEvent?,
    val nextLowTide: TideEvent?,
    val currentSpeedKts: Double?,
    val currentDirectionDeg: Double?,
    val nextCurrentEvent: CurrentEvent?,
    val marineAlerts: List<MarineAlert>,
    val source: SourceReference,
    val observationAgeMinutes: Int?,   // null for a forecast value, set for an observation
    val confidence: Confidence,
)
```

Every field beyond `timestamp`/`location`/`source` is nullable by design (see
[MARINE_SCORING.md](MARINE_SCORING.md) and [PRODUCT.md](PRODUCT.md) — missing data is never
invented). `SourceReference` (`sourceName`, `sourceUrl`, `retrievedAt`, `stationId?`,
`stationDistanceNm?`) travels with the value so the UI can always answer "where did this come
from, and how far away/old is it."

`VesselProfile`, `MarinePlace`, `RouteSample`, `BoatingWindowAssessment` and the scoring types
are specified in full in [MARINE_SCORING.md](MARINE_SCORING.md) and are not duplicated here.

## Time zone handling

Domain/scoring functions take a `ZoneId` as an explicit parameter — never call
`ZoneId.systemDefault()` internally. The application boundary resolves the zone from the
**launch location**, not the device: NWS's own `/points/{lat},{lon}` response conveniently
returns a `timeZone` field for any coordinate, so no separate timezone lookup service is
needed.

## Caching

**As actually built** (corrected from an earlier draft of this doc that described a caching
decorator this project doesn't have): there is no repository-level TTL cache wrapping
`DefaultBoatingRepository.buildAssessment()` yet — each call fans out to every provider fresh.
What does exist, scoped to where it clearly matters:

- `NwsProviders` caches the `/points` → `forecastGridData` URL and resolved `ZoneId` per
  coordinate indefinitely (in-memory, no TTL) — pure geography, never goes stale, mirroring
  RideCast's own NWS grid-cache pattern.
- `NdbcObservationProvider` caches the parsed `latest_obs.txt` snapshot for 10 minutes — it's a
  ~100 KB network-wide file that changes on NDBC's own reporting cadence, not something worth
  re-fetching per screen recomposition.
- `NdbcStationDirectory` caches the id→name lookup for 24 hours — station names essentially
  never change.
- `CoopsTideProvider` caches the full CO-OPS tide-station list (~3,500 stations) for the
  process lifetime once fetched.

A RideCast-style three-tier decorator (in-memory TTL → durable Room fallback → live fetch,
stale-over-error on failure) around the whole assessment is a reasonable next addition once
real usage shows repeated `buildAssessment()` calls for the same plan are common enough to
matter — see "Scale and Provider Risk" in [ROADMAP.md](ROADMAP.md).

## What's deliberately not here yet

WorkManager background refresh, notifications, maps, `MarineFacilityInfoProvider`
implementations, real-time (PORTS) current *observations* (only *predictions* are implemented -
see [DATA_SOURCES.md](DATA_SOURCES.md)), the Inknaut splash's onboarding-check gate, and Mode B
(port-to-port) route generation are all out of scope so far — see [ROADMAP.md](ROADMAP.md).
NDBC observations (`MarineObservationProvider`) shipped in Sprint 2; `CurrentProvider`
(NOAA CO-OPS current predictions), the FWC/USACE place-discovery sources, station
representativeness/environment classification, the alert relevance model, and the first vessel
presets all shipped in Sprint 3 — each along the same "new implementation of an existing seam,
or a new interface consumed by an existing call site" pattern, without a call site that
predates it needing to change shape.
