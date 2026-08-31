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
│   ├── model/                  — GeoPoint, SourceReference, Confidence, Units
│   ├── marine/                 — MarineConditions and its component value types
│   ├── weather/                — GeneralWeatherProvider, WeatherForecast/Hour
│   ├── tide/                   — TideProvider, CurrentProvider, TideEvent, station models
│   ├── alert/                  — MarineAlertProvider, MarineAlert
│   ├── place/                  — MarinePlace, MarinePlaceProvider, SavedLaunch
│   ├── vessel/                 — VesselProfile, VesselType, presets
│   ├── route/                  — RouteSample, RouteSampleRole, BoatingPlan
│   ├── consensus/               — multi-provider merge for MarineConditions
│   ├── scoring/                 — BoatingCategory, Hazard, PointAssessment,
│   │                              BoatingWindowAssessment, MarineScoreEngine
│   └── settings/                — AppSettings, unit preference
├── data/
│   ├── remote/
│   │   ├── nws/                 — points → grid → forecastGridData / forecast/hourly / alerts
│   │   ├── openmeteo/           — general + marine (dev-only, license-gated - see DATA_SOURCES.md)
│   │   ├── coops/                — NOAA CO-OPS tide predictions + station metadata
│   │   └── photon/               — keyless place search
│   ├── local/                    — Room: SavedLaunchEntity, VesselProfileEntity, AppConfigEntity
│   ├── mapper/                   — DTO → domain, one file per provider
│   └── repository/               — provider fan-out + cache decorators, implements domain interfaces
├── ui/
│   ├── theme/                    — WakeWindowTheme, AppearanceMode, CategoryColors
│   ├── splash/                   — InknautSplashScreen
│   ├── navigation/                — WakeWindowNavHost, route constants
│   ├── home/                      — today's assessment for the active saved launch
│   ├── planboat/                  — launch + departure + return time selection flow
│   ├── launchsearch/                — place search (Photon-backed)
│   ├── settings/
│   ├── about/                     — safety disclaimer + Inknaut attribution
│   └── components/                 — shared composables (score card, hazard chip, gauges)
├── AppDependencies.kt              — manual DI composition root (object, RideCast-style)
└── MainActivity.kt
```

`domain/` having zero Android/Compose/Retrofit/Room imports is enforced by convention (not a
lint rule yet — worth adding once the package is stable) and is what keeps a future `:shared`
KMP module split a mechanical move-and-recompile rather than a rewrite, exactly as RideCast's
own `shared` module does today.

## Dependency injection

No framework. `AppDependencies` (top-level object, mirroring RideCast's own) exposes factory
functions (`defaultMarineForecastProvider()`, `openDatabase(context)`,
`defaultBoatingRepository()`, etc.) called from `MainActivity` and ViewModels. As in RideCast,
any two screens that should share a cache must be handed the *same* repository instance —
`AppDependencies` is the one place that decides that, not each screen individually.

## Provider interfaces (domain layer, no implementation detail leaks through)

```kotlin
interface GeneralWeatherProvider {
    suspend fun hourlyForecast(location: GeoPoint, start: Instant, end: Instant): WeatherOutcome
}

interface MarineForecastProvider {
    suspend fun hourlyMarineForecast(location: GeoPoint, start: Instant, end: Instant): MarineForecastOutcome
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
}

interface MarineAlertProvider {
    suspend fun activeAlerts(location: GeoPoint): MarineAlertOutcome
}

interface MarinePlaceProvider {
    suspend fun search(query: String, bias: GeoPoint?): List<MarinePlaceCandidate>
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

A single decorator pattern reused for marine forecast, tide, and place-search repositories:
in-memory TTL cache (location-keyed, not window-keyed) → live fetch, with stale-over-error
fallback on a failed live fetch. The durable (Room-backed) second cache tier RideCast has is
deferred until real usage data shows it's needed — this sprint's TTL cache is enough to avoid
refetching on every recomposition/navigation without adding a persistence layer for something
that goes stale within the hour anyway.

## What's deliberately not here yet

WorkManager background refresh, notifications, maps, NDBC observations, the Inknaut splash's
onboarding-check gate, and Mode B (port-to-port) route generation are all out of this sprint's
scope — see [ROADMAP.md](ROADMAP.md). The provider interfaces above already have the shape to
add `MarineObservationProvider`'s NDBC implementation later without touching call sites.
