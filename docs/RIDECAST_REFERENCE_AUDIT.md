# RideCast → WakeWindow Reference Audit

RideCast (`RideCast.zip`, extracted read-only for this audit; the zip itself was never
modified) is Inknaut Labs' existing motorcycle-commute weather app: "is the weather suitable
for my ride between home and work." It is a mature, single-developer-scale Kotlin/Compose
Android app — 2 Gradle modules, ~19 Room schema versions, 107 unit test files with over 1,100
`@Test` methods, and two written architecture docs of its own (`CLAUDE.md`,
`docs/ARCHITECTURE.md`) that already state a philosophy worth inheriting: native
Android/Compose only, no backend, a pure-Kotlin domain layer, and a deliberate rejection of
"enterprise-style overengineering."

This document is the outcome of a full read-only audit (build system, UI/theme/navigation,
domain/scoring/weather logic, and infrastructure/persistence/testing) and states, concept by
concept, what WakeWindow reuses, adapts, rewrites, or deliberately leaves behind. **Nothing
here is a rename or reskin** — RideCast's product logic (motorcycle commute, home/work,
single-vehicle-hardcoded thresholds) does not carry forward; its *engineering patterns*
substantially do.

One product decision this audit surfaces rather than infers: **WakeWindow does join the
Inknaut Labs publisher family** (confirmed siblings: NextUp — the pilot app — RideCast, and
Abra), per the brief's own direction. Section 5 covers what that means concretely.

---

## 1. Reused conceptually (pattern carries over, implementation is new)

These are engineering decisions RideCast got right in a way that is not specific to
motorcycles or commuting — WakeWindow implements its own version of each, from scratch,
following the same shape.

**Architecture & process**
- Layered package structure: `data/` (remote DTOs/services/mappers per provider, `local/`
  Room, `repository/`), `domain/` (pure Kotlin, zero Android/Compose/Retrofit/Room
  dependency), `ui/` (feature-per-package Compose screens + ViewModels), plus small
  `util`/cross-cutting packages.
- No DI framework. A single manual service-locator object (WakeWindow's own
  `AppDependencies`) wiring constructors by hand — appropriate at this scale, and RideCast's
  own architecture doc explicitly argues against DI "purely for architectural ceremony."
  Reused pattern: callers that share a screen must be handed the *same* repository instance,
  since an in-memory cache decorator is defeated by two separately-constructed instances.
- `CLAUDE.md` task discipline (small, explicitly-scoped tasks; no speculative abstractions;
  "stop and explain before a significant architecture change"; a stated definition of done) —
  WakeWindow gets its own root `CLAUDE.md` with the same rules, marine-domain content.
- Per-use-case sealed `Outcome`/`Result` types sized to exactly the failure modes a boundary
  can produce (e.g. RideCast's `RouteOutcome { Success / NoRoute / Unavailable / Failure }`)
  instead of one generic `Result<T>`/`Resource<T>`/`Either` wrapper threaded everywhere.
  WakeWindow follows the same discipline (its own per-boundary sealed types), and explicitly
  rejects introducing a generic wrapper type.

**Networking**
- Retrofit + OkHttp + kotlinx-serialization, one isolated `Retrofit`/`OkHttpClient` per
  external provider (own `Config`/`Dto`/`Service`/`Mapper`/provider class), not a single
  shared client.
- NWS-specific: a descriptive `User-Agent` interceptor built from a `BuildConfig` contact
  field (never a hardcoded personal identifier — RideCast documents having to fix exactly
  that mistake once), indefinite in-memory caching of the `/points` → grid-URL resolution
  (pure geography, never goes stale) with in-flight de-duplication, and a raised OkHttp
  per-host concurrency cap (default 5 silently serializes a multi-point concurrent fetch).
- Provider abstraction shape: a narrow interface per concern (RideCast's
  `WeatherProvider.getForecast(location, start, end)`), a repository that fans out to every
  configured provider **concurrently** with per-provider `runCatching` (one provider's
  failure never blocks another's result), returning successes and failures as separate lists
  rather than collapsing them.
- Three-tier caching: in-memory TTL cache → durable Room-backed fallback (refreshed
  in the background, never blocking the caller) → live multi-provider fetch, with **stale
  data preferred over an error** on a live-fetch failure ("some real data beats none"),
  and cache keys based on rounded **location**, not the exact requested time window, so a
  wide fetch pre-warms narrower later requests.
- Build-variant-gated paid-provider keys via `BuildConfig` fields sourced from Gradle
  properties/environment variables (never committed), with a release-build Gradle task that
  **fails the build outright** if a required commercial key is blank — directly reused for
  Open-Meteo's commercial-licensing constraint (see [DATA_SOURCES.md](DATA_SOURCES.md)).

**Domain logic**
- Multi-provider consensus: numeric fields averaged, categorical/hazard-relevant fields take
  the **worst case** (never averaged away), risk booleans **OR-combined** (a real signal from
  one provider is never erased by a calmer second provider), wind/wave *direction* combined
  by circular (vector) mean, not naive averaging. Disagreement between providers is flagged
  separately and feeds confidence — it never silently alters the combined value.
- Confidence as an evidence-driven, provider-count-generalizable computation: how many
  independent sources actually agree on the *specific evidence driving the result*, not just
  "did N sources respond somewhere in a wide window." Worst-of rollup across the whole plan
  (a plan is only as trustworthy as its weakest leg/point).
- Hard-gate hazard architecture: formal alerts (from the same NWS CAP feed, same
  severity/certainty/urgency vocabulary) apply a **cap** on the outcome, applied *last*, after
  all other weather-derived penalties/caps — "favorable weather can never overcome an
  applicable severe hazard cap." A second, independent evidence-only severity axis (not
  requiring a formal alert at all) can *also* force the worst outcome. Most-restrictive-wins
  when multiple hazards are simultaneously active.
- Explicit-timezone-parameter discipline: pure domain/scoring functions take a time zone as
  an explicit argument; only the application boundary resolves a "real" zone. WakeWindow
  adapts *where* that zone comes from — the launch location's coordinates (NWS conveniently
  returns `timeZone` in its `/points` response), not the device's local zone, since a
  trailer-boater can easily launch outside their home time zone.
- Fraction-based route sampling over real geometry (locate the bracketing points for a target
  cumulative distance and interpolate, walking the actual route rather than a straight line;
  compute each sample's expected arrival time; match forecast data to the nearest hour
  **within a bounded window**, never substituting a distant hour) generalizes cleanly from
  "4 fixed commute points" to an arbitrary-length boating window.

**Persistence & testing**
- Single Room database, hand-written `Migration(n, n+1)` objects with an explanatory comment
  on each, `exportSchema = true` with the schema JSON committed and exposed as an asset in
  *every* build-type source set (RideCast hit and fixed a real bug where Robolectric migration
  tests silently no-op'd under a non-debug build type because schemas were only wired for
  `debug`).
- No `TypeConverter`s: epoch-millis `Long` / minutes-from-midnight `Int` for times (avoids
  locale-dependent string formatting), enums stored as their `.name` string (so an unknown
  future value can never fail to load), small hand-serialized JSON strings for nested lists.
- Test stack: JUnit4 + `kotlinx-coroutines-test` + OkHttp `MockWebServer` + Robolectric
  (for Room migration tests only) — **no MockK/Mockito, no Turbine**. Hand-written fakes
  implementing the real production interface stand in for mocks; fixture-factory functions
  with sensible named defaults stand in for fixture classes or `@Before` setup. Tests call
  the real production entry point, never a re-implemented "test version" of the algorithm —
  RideCast's `DayScoreEngine.decomposeLeg()` exposes a read-only breakdown of the *same*
  internal computation for test introspection, a pattern WakeWindow's scoring engine repeats.
  Heavy investment in `domain/` unit tests, deliberately minimal instrumented/UI test
  footprint in early milestones.
- Named-scenario regression tests that assert a design decision still holds for a specific,
  documented real-world scenario, alongside adversarial "integrity audit" tests that exercise
  the full pipeline (provider mapping → consensus → temporal matching → sampling → hazard
  attribution → aggregation) looking for internally inconsistent results.

## 2. Adapted (real starting point, meaningfully changed for marine/boating use)

- **Kotlin/AGP/Compose/library versions.** RideCast's proven combination (Kotlin 2.0.20, AGP
  8.5.2, KSP `2.0.20-1.0.25`, Compose BOM 2024.09.00, Gradle 8.7, Retrofit 2.11.0, OkHttp
  4.12.0, Room 2.6.1, Navigation Compose 2.8.0) is confirmed to work on this exact machine's
  SDK install. WakeWindow starts from those exact pins (see [ARCHITECTURE.md](ARCHITECTURE.md))
  rather than the newest available (AGP 9.x / Kotlin 2.4.x are current but unproven here, and
  build reliability matters more this sprint than being on the latest release) — with a
  version catalog added from day one, since RideCast's hardcoded-string-per-module dependency
  declarations are a real, if minor, gap in its own setup.
- **Retrofit/manual-DI service-locator pattern → WakeWindow's own `AppDependencies`.** Same
  shape, new factory methods for marine providers.
- **Open-Meteo integration.** RideCast only calls the land forecast endpoint; WakeWindow adds
  the separate Marine API (`marine-api.open-meteo.com`) alongside it, and treats the whole
  Open-Meteo integration as commercially-constrained (see [DATA_SOURCES.md](DATA_SOURCES.md)).
- **Geocoding.** RideCast's dual free/commercial provider strategy (Photon for keyless
  search-as-you-type, a paid provider gated to release builds) is reused, but the *place*
  model changes: RideCast's `SavedPlaceRole` is `HOME`/`WORK` (at most one each); WakeWindow
  needs an open-ended set of saved launches (ramps, marinas, docks), so the unique-role-index
  trick doesn't apply as-is.
- **Route-sampling geometry.** The fraction-based real-geometry sampler is reused (§1); its
  rigid 4-point enum is not (§3).
- **Settings/persistence shape.** RideCast's three-way split — a single-row app-config entity,
  a separate saved-places table, and commute windows as their own domain type — is a
  reasonable model, but its single-user/single-commute assumption (one HOME, one WORK, one
  set of commute windows) must loosen into multi-launch, multi-vessel, per-plan windows.
- **NWS integration.** The point→grid resolution mechanism, caching, and User-Agent handling
  are reused; RideCast's actual data path (only ever call `/forecast/hourly`, parse free-text
  `shortForecast` for condition/severity) does not, because that endpoint doesn't work at all
  for marine points (see §3 and [DATA_SOURCES.md](DATA_SOURCES.md)).

## 3. Rewritten (RideCast's version doesn't transfer; the *slot* it fills does)

- **Domain models.** `MarineConditions` (wind/gust/waves/swell/tide/current/visibility/marine
  alerts, nullable per-field, provider/observation-age/confidence-tagged) replaces RideCast's
  land-weather `ForecastHour` — marine data has structurally different fields, different
  availability gaps, and different provenance requirements than a commute forecast does. See
  [ARCHITECTURE.md](ARCHITECTURE.md).
- **NWS marine data path.** RideCast never calls `forecastGridData` or handles the fact that
  `/forecast`/`/forecast/hourly` return HTTP 404 `MarineForecastNotSupported` for any point
  NWS classifies as `type: marine`. WakeWindow's NWS client branches on that classification
  and reads the raw gridded numeric fields (`waveHeight`, `windWaveHeight`,
  `primarySwellHeight`/`Direction`, `twentyFootWindSpeed`, etc.) via interval-based
  `validTime` parsing — a genuinely different parser than RideCast's simple hourly-periods
  array. Full detail in [DATA_SOURCES.md](DATA_SOURCES.md).
- **Hazard set.** RideCast's hazards (rain, wind-chill/heat on an exposed rider, thunderstorm,
  formal NWS land alerts) are replaced by marine hazards: Small Craft Advisory, Gale/Storm/
  Hurricane Warning, wave-height thresholds, visibility/fog, and thunderstorm-over-water —
  arguably an even harder gate on open water than on a road, since there's no shoulder to pull
  onto. The two-tier "formal alert cap + evidence-only severity axis, both able to force the
  worst outcome, most-restrictive-wins" *mechanism* is reused (§1); the specific hazards
  plugged into it are new. See [MARINE_SCORING.md](MARINE_SCORING.md).
- **Scoring thresholds and — most importantly — vessel parameterization.** RideCast hardcodes
  one global `ScoringConfig` tuned to an exposed motorcycle rider; there is no per-vehicle
  concept anywhere in it. WakeWindow's scoring engine takes every threshold (wind/gust/wave/
  thunderstorm/visibility tolerance) from a `VesselProfile` parameter — a pontoon, a PWC, and
  a 34' cruiser run the *same* scoring function with different inputs, never a different
  hardcoded engine per boat.
- **Return-leg weighting.** RideCast blends two roughly-symmetric legs (morning/return
  commute) as a risk-weighted average favoring the worse leg. A boating day is not
  symmetric — the return leg compounds fatigue, afternoon thunderstorm buildup, and (unlike a
  commute) offers no "pull over," so WakeWindow's window aggregation treats return-proximity
  as its own explicit weighting term rather than reusing RideCast's two-leg blend formula
  as-is. See [MARINE_SCORING.md](MARINE_SCORING.md).
- **Route sample roles.** RideCast's `RouteSampleType` enum is a hardcoded, structurally
  rigid 4-value set (`HOME/ROUTE_33/ROUTE_66/WORK`) — a `RouteSamples` collection is invalid
  unless it contains exactly those four. WakeWindow's `RouteSample` carries an open-ended
  `role` (`DEPARTURE/UNDERWAY/WAYPOINT/DESTINATION/RETURN`) over an arbitrary-length list, as
  the brief specifies.
- **Business logic embedded in navigation composables.** RideCast's `RideCastNavigation.kt`
  (700 lines) has real fetch/derivation logic (30-100+ lines per route) living directly inside
  `composable{}` blocks for several screens — which its own `docs/ARCHITECTURE.md` explicitly
  warns against elsewhere in the same repo. WakeWindow pushes all such orchestration into
  ViewModels from the start rather than inheriting the shortcut.
- **Accessibility coverage.** RideCast's semantics/`contentDescription` usage is sparse and ad
  hoc (roughly 30 real content descriptions, 3 `semantics{}` blocks, no accessibility tests).
  WakeWindow — a safety-relevant go/no-go tool exactly like RideCast is — treats a systematic
  accessibility pass on every custom composable (score cards, gauges, hazard chips) as a
  first-class requirement rather than an afterthought.

## 4. Explicitly not carried into WakeWindow

- **The KMP `shared` module split.** RideCast physically separates its pure domain/scoring
  logic into a Kotlin Multiplatform module (`androidTarget()` + a bare `jvm()` target, staged
  for an eventual iOS port). WakeWindow keeps the *discipline* (domain code has zero Android
  import) but, for this sprint, as plain packages inside the single `:app` module — a full KMP
  module adds real Gradle/toolchain complexity for an iOS target that doesn't exist yet, and
  the MVP boundary explicitly calls for a working vertical slice over architectural
  preparation for a platform not being built. This is the one clearly-reusable pattern from
  the audit that is deliberately deferred rather than adopted immediately; splitting into a
  `:shared` module later is a mechanical refactor, not a rewrite, if/when iOS becomes real.
- **`entitlement/` (unused paid-tier scaffolding).** Dead code in RideCast today, guarding
  against a paid tier that doesn't exist yet. Skipped per the same "avoid speculative
  abstractions for features that do not exist yet" rule RideCast's own `CLAUDE.md` states.
- **osmdroid / any map rendering.** No map screen is in this sprint's MVP boundary at all;
  RideCast's keyless-maps rationale is noted in [ROADMAP.md](ROADMAP.md) for when a map screen
  is actually built, but nothing is wired now.
- **WorkManager background refresh / notification scheduling.** A genuinely good pattern
  (self-re-arming daily "check your day" `OneTimeWorkRequest`, 30-minute periodic refresh) —
  explicitly deferred to the next sprint (see [ROADMAP.md](ROADMAP.md)) rather than built now,
  since it isn't part of the 14-point MVP boundary and RideCast's own rule against adding
  dependencies not required by the current task applies here too.
- **The foreground GPS ride-observation service.** Narrow, motorcycle-specific (learning real
  commute duration from live tracking). Not reused unless/until WakeWindow does live on-water
  session tracking, which is not in scope now.
- **RideCast's own product palette (Asphalt/Amber "motorcycle instrumentation" theme).**
  Explicitly rejected — WakeWindow needs its own nautical identity (§5), not a recolor of
  RideCast's. The *mechanism* for theming (explicit, non-dynamic light/dark `ColorScheme`s, so
  a hazard/warning color is never re-tinted by device wallpaper; a persisted `AppearanceMode`
  distinct from raw `isSystemInDarkTheme()`) is reused — only the actual hex values are not.
- **A generic `Result<T>`/`Resource<T>` wrapper.** Not present in RideCast and deliberately
  not introduced in WakeWindow either — see the per-use-case sealed type discipline in §1.
- **NDBC buoy observations and USCG Local Notices to Mariners parsing.** Have no RideCast
  analog at all (RideCast has no observational-station concept), and are explicitly deferred
  per [ROADMAP.md](ROADMAP.md) — the `MarineObservationProvider` interface exists, but no
  concrete NDBC implementation ships this sprint.

## 5. Inknaut Labs brand family

Per the brief's own direction, WakeWindow **joins** the Inknaut Labs family alongside NextUp
(the pilot app), RideCast, and Abra. RideCast's `branding/inknaut/` directory is the
canonical, publisher-owned brand kit (`INKNAUT_BRAND_SPEC.md` plus raster-only PNG assets —
symbol, stacked lockup, splash canvases, light+dark) explicitly designed to be copied
unmodified into every sibling app rather than regenerated. WakeWindow copies that directory
as-is (see [ARCHITECTURE.md](ARCHITECTURE.md) for where it lands) and implements its own
splash/About treatment against the same spec — the spec's own §7 governance rule (**publisher
Navy/Blue are for startup, About, and reusable brand assets only — never a product's own
palette**) is exactly why WakeWindow's nautical palette (deep navy/teal/cyan — see
[PRODUCT.md](PRODUCT.md)) and RideCast's amber/asphalt palette can coexist as clearly
different products under one visible publisher mark, precisely as RideCast itself does today.
