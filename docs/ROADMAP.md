# WakeWindow — Roadmap

This roadmap separates what's actually built from what the architecture merely *makes room
for*. Building every seam listed under "Future" now would be architectural theatre; the goal
of each sprint is a real, working vertical slice.

## Sprint 1 — MVP vertical slice (complete)

The target was: install WakeWindow on a device, pick a real launch point, pick departure and
return times, and get a genuine boating-day assessment built from live NWS/marine/tide data,
with reasons, with a saved launch that persists. All 14 items shipped and were verified live
on a Pixel emulator against real Port Canaveral, FL data.

## Sprint 2 — Marine data hardening + launch intelligence (complete)

Goal: not more screens, but making the assessment *trustworthy* — stronger evidence, honest
uncertainty, and the start of the launch-intelligence system. Shipped:

1. Marine alerts wired end-to-end (provider → domain → scoring → UI), with a
   vessel-size-exemption distinction (Small Craft Advisory exempts non-small vessels; Dense Fog
   Advisory and everything else does not) — see [MARINE_SCORING.md](MARINE_SCORING.md).
2. NOAA NDBC buoy observations (`MarineObservationProvider`), with real station selection
   (capability + freshness + distance, not just nearest) and a documented staleness policy —
   see [DATA_SOURCES.md](DATA_SOURCES.md).
3. Forecast-vs-observation disagreement detection (`MarineDisagreementDetector`), confirmed
   live against a real buoy reading that materially disagreed with the forecast.
4. A full audit of the Port Canaveral `97 — EXCELLENT` result and a second, deliberately
   degraded inland-lake scenario, both documented with real captured data in
   [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md).
5. A real Sprint 1 correctness bug fixed: a failed marine-alert check used to be
   indistinguishable from "checked, zero active" — it now explicitly downgrades confidence
   instead.
6. Tide trend fixed — `TideTrend` gained explicit `NEAR_HIGH`/`NEAR_LOW`/`UNKNOWN` states so it
   is never left as an unexplained blank.
7. Launch-intelligence domain model (`MarineFacilityInfo`, `FacilityAvailability`,
   `SourceType`) and a first `LaunchInfoScreen` that renders honestly even with zero verified
   data — no facility-data provider ships yet, by design (see "Launch intelligence" below).
8. Best Window is now a genuine recommendation with deterministic reasons, and only labeled
   "Best Window" when it's actually different from what the user planned — otherwise the UI
   says the planned window is already excellent.
9. Confidence is now an evidence checklist (which sources actually contributed) plus
   plain-language limitations, not just a bare HIGH/MEDIUM/LOW badge.
10. Date + outing-duration added to the plan screen (previously time-of-day only).

## Sprint 3 — Decision intelligence + real launch discovery (complete)

Goal: does WakeWindow understand *where its evidence came from* and whether that evidence
actually represents the user's boating environment? Shipped:

1. Forecast-vs-observation comparison fixed to compare **forecast-at-station vs.
   observed-at-station**, never launch-vs-buoy (a real Sprint 2 methodology bug) — see
   [STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md).
2. `WaterEnvironment` classification and `StationRepresentativeness` scoring, so a station's
   distance/environment match to the launch is an explicit, UI-visible fact, not implicit.
3. A fresh, representative, materially-worse observation can now influence the departure-hour
   category via an explicit hazard/gate (`ObservationalCautionEvaluator`) — never by averaging.
4. Environment-aware evidence requirements (`EvidenceRequirementEvaluator`) — missing wave data
   ceilings a coastal launch's category at `GOOD`; an inland lake is never penalized for
   evidence that was never going to exist there.
5. The alert relevance model (`MarineAlertImpact`) replaced Sprint 2's blanket "any advisory
   caps at CAUTION" policy — see [MARINE_SCORING.md](MARINE_SCORING.md) "Alert relevance
   model." The Small Craft Advisory vessel-size exemption was removed (it's always surfaced and
   always gates now, regardless of vessel size).
6. `CurrentProvider` implemented via NOAA CO-OPS current predictions (`CoopsCurrentProvider`),
   including flood/ebb/slack event modeling (`CurrentTimeline`) — see
   [DATA_SOURCES.md](DATA_SOURCES.md).
7. Real boating-relevant place discovery: Florida FWC boat ramp inventory and USACE
   recreation-area data, fanned out and ranked ahead of Photon
   (`CompositeMarinePlaceProvider`) — see [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md).
8. The first five user-selectable vessel presets, persisted, displayed as a compact chip row.
9. Port Canaveral and Clinton Lake, KS both re-validated live against the corrected
   methodology, honestly — including a real duplicate-hazard-display bug (nine identical Heat
   Advisory entries for one nine-hour outing) found and fixed *during* this live
   re-validation, not before it. See [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md).

## Sprint 4 — productization: launch intelligence, vessel personalization, planning UX, scale hardening (complete, verified)

Goal: move WakeWindow from "excellent technical prototype" toward something a real boat owner
would trust on a Saturday morning. **This sprint originally ran in an online Claude Code
environment with no outbound network access to any provider host or to Android's/Google's
Maven repository**, so nothing below was compiled, run, or live-validated when first written.
**Sprint 4.5 (a local session with full SDK/network access) has since compiled, tested, and
live-validated it — see "Sprint 4.5 — local verification and repair" below for the full
account, including eight real bugs found and fixed.** Shipped:

1. **Real FWC facility intelligence** — `FwcFacilityInfoProvider`, the first real
   `MarineFacilityInfoProvider`, wiring `TotalLanes`, `ContactPhone`, `RampType`, `AccessType`,
   `Amenities`, `WaterBodyName`, and a classified `Status` through into `MarineFacilityInfo`,
   with provenance (`SourceReference.recordId` = FWC's `OBJECTID`) and a 7-day durable cache —
   see [DATA_SOURCES.md](DATA_SOURCES.md). `LaunchInfoScreen` redesigned into
   Access/Facilities/Contact/Location/Source sections with compact known-fact chips and
   collapsed-by-default unknown fields.
2. **Boating-centric search** — three-tier ranking (source authority, then place-type boating
   relevance, then proximity), location bias from the active/last-saved launch (no device GPS
   permission required or requested), and generalized cross-source duplicate reconciliation
   (FWC/USACE/Photon, not just geocoding-vs-authoritative) — see
   [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md).
3. **Custom vessel profiles** — `VesselProfileScreen`, Room-backed multi-profile persistence,
   explicit "planning preferences/comfort thresholds, never safe limits" language throughout —
   see [VESSEL_PROFILES.md](VESSEL_PROFILES.md) and [MARINE_SCORING.md](MARINE_SCORING.md).
4. **Planning UX** — quick-plan shortcuts (Morning/Afternoon/Evening/Full day) driven by real
   local sunrise/sunset where available, a plan-summary card, a whole-outing tide/current
   timeline (explicitly labeled "prediction"), and "usually 7 AM · 6h" recall on saved launches
   from the last plan actually run there — see [PLANNING.md](PLANNING.md).
5. **Sunrise/sunset/civil twilight** — `SolarCalculator`, a pure-Kotlin closed-form
   approximation, no new dependency or paid API — see [PLANNING.md](PLANNING.md) for its
   verification caveat (qualitative tests only; no live network to check against a real
   almanac this sprint).
6. **Mode B foundation, domain only** — `MarineTripPlan`/`PlanningWaypoint`/`TripLegEstimator`,
   explicitly non-navigation language throughout, geodesic "planning distance," graceful
   ETA degradation with no cruise speed — see [TRIP_PLANNING.md](TRIP_PLANNING.md). No UI ships
   this sprint, and per-waypoint weather fetching is not wired into
   `DefaultBoatingRepository` — both are honest, explicit gaps.
7. **Durable cache + request coalescing** — `DurableCache`/`RequestCoalescer`
   (`data/cache/`), wired into FWC facility lookups and place search; the full weather/alert/
   tide fan-out is deliberately **not** cached yet — see [CACHE_POLICY.md](CACHE_POLICY.md) for
   exactly what's wired vs. documented-only and why (alerts specifically need their own
   staleness review before caching, not a default TTL).
8. **Provider resilience** — new tests proving a *throwing* provider (not just a well-behaved
   `Failure` outcome) never crashes the assessment, individually and with every provider
   throwing simultaneously (`DefaultBoatingRepositoryTest`).
9. **Open-Meteo structurally removable** — `AppDependencies.boatingRepository(includeOpenMeteo
   = false)` builds an NWS-only configuration; existing single-provider fake-based tests are the
   regression coverage proving it still produces a real assessment — see
   [DATA_SOURCES.md](DATA_SOURCES.md) "Remove structural Open-Meteo dependency."
10. **Production geocoder decision record** — Photon stays the development/fallback geocoder,
    unchanged; a real replacement evaluation needs network access this session didn't have, and
    committing to a paid vendor without that verification (or the user's sign-off on a
    cost-bearing dependency) was explicitly out of scope — see [DATA_SOURCES.md](DATA_SOURCES.md).

**Not attempted, or only partially, and why:** USACE facility-data re-investigation (no
network access to inspect further live services at the time); a full "GOOD TODAY" Home-screen
condition badge per saved launch (would need either durable serialization of the full
assessment graph or a user-triggered-refresh design — descoped to the lighter "usually 7 AM ·
6h" recall instead, which needed neither); broad accessibility/UI-polish audit beyond the
screens directly touched this sprint. Physical-device UX validation was attempted in Sprint 4.5
but a Razr Ultra was never connected (only an emulator was available, and the sprint brief
explicitly says not to substitute one by accident) — still genuinely not done, see below.

## Sprint 4.5 — local verification and repair (complete)

Goal: actually compile, test, and live-validate everything Sprint 4 shipped unverified, fixing
root causes rather than merely reporting them. Found and fixed eight real bugs, all covered by
new or updated unit tests (205 → 271 → 283 tests across Sprint 4 → 4.5):

1. **`VesselProfile.presets()`** — the "Small recreational boat" preset was built via
   `default().copy(name = ...)`, which doesn't recompute the `id = name` default parameter, so
   its `id` stayed `"Recreational boat (default)"` while its `name` changed — broke the "a
   preset's `id` is its own `name`" invariant `markCustomized()` depends on.
2. **`SolarCalculator`** — the day-rollover correction was missing: a west-longitude evening
   event (sunset) routinely lands after UTC midnight, but the algorithm always anchored it to
   the same UTC calendar day as the morning event, so Port Canaveral's computed sunset came out
   *before* sunrise. Fixed and re-verified against Port Canaveral, Boston (summer/winter),
   equator, and polar cases.
3. **`FwcMapper.toFacilityInfo`** — live FWC data (re-verified 2026-08-31, ~275-record sample
   across six Florida counties) showed ~23% of records carry the literal string `"NA"` for
   `ContactPhone` rather than leaving it null. Now treated as absent, matching NDBC's own
   `"MM"`-marker precedent — see [DATA_SOURCES.md](DATA_SOURCES.md).
4. **`FwcMapper.whereClauseFor`** — FWC's own `City` field always spells "Saint"/"Fort" out in
   full (confirmed live, no exceptions found); a user typing the far more common abbreviated
   form ("St Petersburg", "Ft Myers") got zero FWC matches and fell straight through to
   unranked Photon noise (live-verified: Petersburg, Alaska and Russian university results
   ranked ahead of the actual Florida city). Query normalization fixes it for both forms.
5. **`DurableCache.getOrFetch`** — a cache *hit's* `deserialize()` call was outside the
   try/catch, so a corrupted cached payload threw uncaught instead of degrading to a miss.
6. **`WakeWindowViewModel.search()`** — no try/catch around `placeProvider.search()`, and no
   `CoroutineExceptionHandler` exists anywhere in the app — the DurableCache bug above made this
   a real, reachable crash, not just a theoretical one. Now caught like `viewLaunchInfo()`.
7. **`WakeWindowViewModel.setReturnTime()`** — no validation, unlike `setDepartureTime()`'s
   existing guard; the manual Return picker could produce a return time at or before departure.
8. **Tide/current station provenance** — `MarineConditions` carries one shared `source` field,
   and the merge order always put a plain weather reading first, so tide/current station
   identity never survived merging even before reaching the UI — contradicting
   [DATA_SOURCES.md](DATA_SOURCES.md)'s explicit requirement. Fixed by adding
   `nearestTideStation`/`nearestCurrentStation` directly to `BoatingWindowAssessment` (tide and
   current stations are frequently *different* physical stations, so a shared field would have
   risked mislabeling one with the other's identity) rather than patching the merge itself.

Also: moved `VesselProfile.markCustomized()` from a private UI-layer function into the domain
layer (matching `withNewId()`), made `WakeWindowViewModel.saveVesselProfile()` call it as a real
backstop rather than trusting the caller; fixed a `RequestCoalescer` cleanup race where an
already-cancelled caller could permanently leak its in-flight map entry (wrapped in
`NonCancellable`, the standard kotlinx.coroutines idiom); added a `ThrowingMarineProvider` test
for symmetry with existing general-provider throw coverage; fixed 10 `DefaultLocale` lint
warnings in Sprint 4's own new code.

**Confirmed working as designed, not bugs:** the alert-gate/vessel-tolerance interaction (an
official marine warning cannot be neutralized by a permissive vessel profile — traced through
`MarinePointScorer`'s severity-max gating); `FacilityAvailability`'s UNKNOWN/NOT_AVAILABLE/
NOT_APPLICABLE distinctness in `LaunchInfoScreen`; the 150 NM tide / 50 NM current station
cutoffs; `DefaultBoatingRepositoryTest`'s existing throwing-provider coverage for every
provider except marine forecast (see fix 8 above's sibling test).

**Known remaining gap, not fixed this sprint:** two saved custom vessel profiles can share an
identical name with no warning — cosmetic (IDs stay distinct, no data corruption), left as a
minor UX nicety rather than in-scope for a verification/repair sprint.

## Sprint 5 — real trip planning (Mode B), timed waypoint weather, cache expansion (complete)

Goal: turn Mode B from a domain-only placeholder into a real, useful, tested planning feature -
exactly the "next sprint" items 2 and 3 above - while never touching Sprint 4.5's verified Mode
A baseline. Start commit `5ffae18`. Shipped (see [TRIP_PLANNING.md](TRIP_PLANNING.md) and
[TRIP_ASSESSMENT.md](TRIP_ASSESSMENT.md) for the full account):

1. **Real per-point timed weather** (`DefaultTripBoatingRepository`) — every trip point
   (departure, generated weather samples, user waypoints, destination) is fetched and scored at
   its own location and own expected arrival time, never a shared departure-hour snapshot. Live-
   validated against real NWS/CO-OPS/NDBC data for a short coastal trip, a longer three-point
   coastal trip, and a real non-tidal inland lake (see "Live validation" below).
2. **Worst-case trip-level gating** (`TripAssessmentBuilder`, a new pure combiner - deliberately
   not a reuse of `MarineScoreEngine.assess()`, whose return-weighted scoring doesn't generalize
   to a one-way multi-leg trip, see TRIP_ASSESSMENT.md) — one hazardous segment always determines
   the overall category, never averaged away by calm segments on either side. Live-confirmed: a
   real trip with an EXCELLENT departure, a CAUTION waypoint, and an EXCELLENT destination
   correctly resolved to overall CAUTION.
3. **Deterministic intermediate weather sampling** (`WeatherSampleGenerator`) on long legs, always
   carrying `RouteSampleRole.WEATHER_SAMPLE` and kept UI-distinct from a user-chosen planning
   waypoint throughout — never presented as a recommended stop.
4. **Time- and location-aware observation/tide/current relevance** — a buoy observation is only
   fetched for a point within a 3-hour near-term window of "now" (an observation always
   describes *right now*, never a future arrival); each point resolves its own nearest tide/
   current station independently rather than reusing one shared station.
5. **Trip complexity limits and honest forecast-horizon behavior** (`TripPlanLimits`) — documented
   ceilings (10 waypoints, 3 weather samples/leg, 7-day forecast horizon, 14-day max trip
   duration), never a silent failure or a fabricated forecast beyond what a provider can
   actually support.
6. **A real trip editor and result screen** (`ui/tripplan/TripPlanScreen`, `ui/tripresult/TripResultScreen`),
   a distinct "Trip" entry point alongside "Day outing" on Home, and a "Saved trips" section
   mirroring "Saved launches" — reusing the existing place-search screen for waypoint picking
   rather than building a second one.
7. **Local saved-trip persistence** (`SavedTrip`/`SavedTripRepository`/`SavedTripEntity`, Room
   schema version 2 → 3, no migration written — same pre-release/no-real-installs rationale as
   the 1 → 2 bump) — a trip is remembered automatically after a successful assessment, exactly
   like Mode A's "usually 7 AM" launch recall.
8. **Durable cache expansion** to NWS/Open-Meteo general and marine forecast (45 min), NWS
   alerts (7 min, after a specific safety review — see CACHE_POLICY.md "Safety-critical alert
   caching"), and CO-OPS tide/current station metadata (7 days) and predictions (6 hours) — see
   [CACHE_POLICY.md](CACHE_POLICY.md) for the full TTL table and rationale, and for the specific,
   newly-found reason NDBC observations were deliberately left un-cached this sprint (a cached
   "age minutes" field would silently go stale between fetch and a later cache hit).
9. **Request coalescing extended to trip mode** — every newly-cached provider also
   request-coalesces, so a multi-waypoint trip's concurrent per-point fan-out never issues
   duplicate simultaneous network calls for the same NWS grid cell or CO-OPS station.
10. Non-navigation language audited throughout Mode B's new UI and domain code — "planning
    distance," "planning waypoint," "weather sample" (never "waypoint" for a generated point),
    the sprint brief's own disclaimer text shown directly in the trip editor.

**Live validation** (real network, this session; not part of the committed test suite - kept
as temporary scratch files, deleted after use, matching this codebase's "hand-written fakes
only" testing philosophy for the *committed* suite):

- Short real coastal trip (Port Canaveral → Sebastian Inlet): resolved a real NDBC buoy (41113),
  a real tide station (Trident Pier), correct HARBOR/NEARSHORE environment classification per
  point, and correctly time-gated observation relevance.
- Longer real three-point coastal trip (Port Canaveral → Sebastian Inlet → Fort Pierce Inlet,
  ~90 NM total): correctly generated one weather sample per ~25-35 NM leg, resolved three
  distinct real tide stations and one real current station shared only where it was genuinely
  nearest, and correctly worst-cased overall to CAUTION from a single CAUTION waypoint between
  two EXCELLENT endpoints.
- Real non-tidal inland lake (Clinton Lake, KS): confirmed zero fabricated tide/current data
  (`tideStation=null`, `currentStation=null`, `tideHeightFt=null`, `currentSpeedKts=null`) for a
  water body that genuinely has none — INLAND environment correctly classified both ends.

**Test count: 283 → 316** (33 new tests: `MarineTripPlanTest` limit/id/duration additions,
`WeatherSampleGeneratorTest`, `TripAssessmentBuilderTest`, `DefaultTripBoatingRepositoryTest`,
`CachedProvidersTest`, `SavedTripMapperTest`).

**Not attempted, or deferred, and why:** the alternative-departure-window scan (Phase 13, an
explicit stretch goal in the sprint brief); per-point timezone resolution/display for a
cross-timezone trip (every point renders in the departure zone — a real, documented limitation,
see TRIP_ASSESSMENT.md); physical-device/emulator validation (no Android device or emulator was
available in this session, only real network access — the physical-device UX pass from Sprint 4
still hasn't happened across three sprints now and should be prioritized before further UI
work); NDBC observation durable caching (see above).

## Next sprint (highest-value follow-on)

1. **Physical-device UX pass** — still hasn't happened across three sprints now (Sprint 4,
   Sprint 4.5, Sprint 5 all lacked a real Android device/emulator); do it before further UI
   work, not after. Sprint 5's new trip editor/result screens have compiled and passed unit
   tests but have never been visually verified on a real screen.
2. The alternative-departure-window scan (Sprint 5 Phase 13 stretch goal) — the domain model is
   ready; see TRIP_ASSESSMENT.md "Not attempted this sprint."
3. Per-point timezone resolution/display for trips that cross time zones.
4. NDBC observation durable caching, once age-at-read-time (rather than age-at-fetch-time) is
   worked out — see CACHE_POLICY.md.
5. Real-time current *observations* (PORTS-equipped stations), distinct from the
   predictions-only implementation shipped in Sprint 3.
6. A genuine paid-geocoder evaluation (Geoapify/LocationIQ/Mapbox or similar), plus the user's
   sign-off on any cost-bearing dependency.
7. Optional polish: warn on/prevent duplicate custom vessel-profile names (see Sprint 4.5's
   known remaining gap above).

## Launch intelligence — deliberately not a web scraper

`MarineFacilityInfoProvider` has zero implementations by design this sprint (see
[DATA_SOURCES.md](DATA_SOURCES.md) "Marine place / launch intelligence"). Scraping arbitrary
marina/harbor-authority websites is explicitly rejected as an approach: fragile against layout
changes, legally ambiguous (ToS, robots.txt, copyright on facility descriptions), and
unmaintainable once dozens/hundreds of sites are involved. Candidate controlled sources for a
future sprint, none yet evaluated for licensing terms or API availability:

- Official port/harbor-authority websites or APIs where one exists and terms permit reuse
  (`SourceType.OFFICIAL_PORT`).
- **Florida FWC's boat ramp inventory (`SourceType.STATE_AGENCY`) — the closest thing to a
  ready-made source, evaluated for *discovery* in Sprint 3.** Its own schema already carries
  `RampType`, `AccessType`, `TotalLanes`, `Amenities`, and `ContactPhone` — genuine facility
  fields, not just a name/coordinate — that Sprint 3's `FwcMapper` currently discards down to a
  bare `MarinePlaceCandidate`. Wiring these through into `MarineFacilityInfo` is real, scoped
  work for Florida launches, not a new integration.
- USACE recreation-area data (`SourceType.USACE`) — **evaluated in Sprint 3 and found not to be
  a facility-intelligence source**: it identifies that a Corps-managed recreation area exists
  near a query (used for place *discovery* — see [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md)), but
  carries no field asserting a ramp, its lane count, or its amenities. A real USACE facility
  source, if one exists, would need to be a different dataset than the one integrated this
  sprint.
- A WakeWindow-curated dataset for a small number of high-traffic launches, manually verified
  and dated (`SourceType.USER_PROVIDED`/an internal curation source type), as a bootstrap
  before any automated source exists.

## Scale and Provider Risk

Not built yet — this section records what would need attention before real user growth, so
scale problems aren't discovered for the first time in production. All figures are estimates
based on the request pattern in `DefaultBoatingRepository.buildAssessment()`, which as of
Sprint 3 makes on the order of 10-12 outbound calls per assessment (NWS grid, NWS alerts,
Open-Meteo general, Open-Meteo marine, CO-OPS tide station list + predictions, CO-OPS current
station list + predictions, NDBC snapshot, NDBC station names, plus a **second**, narrower
general+marine fetch at the observation station's own coordinates for the forecast-vs-
observation comparison when a station is available). As of Sprint 5 the general/marine/alert/
tide/current legs of that fan-out are durably cached and request-coalesced — see
[CACHE_POLICY.md](CACHE_POLICY.md) — so a repeat or simultaneous-duplicate call for the same
location/window is now cheap; a genuinely new location/window is not, and still pays the full
per-call cost.

**Trip mode's per-point fan-out (Sprint 5):** `DefaultTripBoatingRepository` repeats
Mode A's *shape* of fetch (general, marine, alerts, tide station+events, current
station+events, point type, sometimes an observation) independently **per trip point** — roughly
7-8 outbound calls per point with a single general/marine provider configured (more with
Open-Meteo also enabled), plus a further ~4 for any point close enough to "now" to trigger an
observation comparison. This scales linearly with point count for genuinely distinct locations:
an untried 2-point trip is roughly comparable to one Mode A assessment; a longer multi-waypoint
trip with several generated weather samples (see TRIP_ASSESSMENT.md) is proportionally more.
Caching/coalescing (Sprint 5) remove the *duplicate* cost when multiple points round to the same
NWS grid cell or CO-OPS station (a real, common case for waypoints a few miles apart) but do not
reduce the base fan-out for points that are genuinely far apart. No formal before/after call-count
measurement was taken this sprint (no request-counting harness exists yet) — this is a structural
estimate from the code's own fetch pattern, not a benchmark; building a real counting harness is
worthwhile follow-up before trip mode sees heavier use.

| Users (concurrent-ish daily actives) | Primary risk | Notes |
|---|---|---|
| ~500 | Photon (`photon.komoot.io`) | A shared public demo instance with no SLA; RideCast's own docs already flag this as a pre-beta P0. WakeWindow inherits the identical risk the moment more than a handful of people search launches concurrently. |
| ~1,000 | NDBC `latest_obs.txt` refetch rate | The 10-minute in-memory cache is per-process (per device), not shared — every device independently re-downloads the same ~100 KB network-wide file. At this scale it's still almost certainly fine for NDBC's own infrastructure, but there's no server-side layer to fall back on if it isn't. |
| ~5,000 | Duplicate/redundant API requests | Two people planning the same popular launch (e.g. Port Canaveral) at the same time each trigger a full independent fetch — no request de-duplication or shared cache exists across users, only RideCast-style per-coordinate caches scoped to a single app process. This is the most likely first real problem. |
| ~10,000 | NWS usage etiquette; CO-OPS station-list bandwidth | NWS has no hard published quota but does ask for good-citizen behavior (descriptive `User-Agent`, avoid unnecessary repeated calls) — at this volume WakeWindow should be able to show it isn't hammering `api.weather.gov` per-device with zero server-side aggregation. The CO-OPS ~3,500-station list (fetched once per process, ~2 MB) becomes a real aggregate bandwidth cost multiplied across many fresh app installs/updates. |

**Still not built:** a backend or a shared/cross-device cache server - that's real
infrastructure work that isn't justified before there's a real user base to serve, and would
be exactly the "architectural theatre" this roadmap's own opening paragraph warns against.
**Built in Sprint 4:** a process-local durable (Room-backed) cache and in-process request
coalescing (`data/cache/DurableCache`/`RequestCoalescer`) - see
[CACHE_POLICY.md](CACHE_POLICY.md) for exactly which data is wired through it (FWC facility
info, place search) versus still fetched fresh every time (the weather/tide/current/alert
fan-out) and why. This addresses the "two people planning Port Canaveral on two different
devices" case not at all (that needs a shared backend, still out of scope) but does address
"the same device re-fetching the same thing repeatedly," which was the more immediate,
per-process cost. Swapping Photon for a production-appropriate geocoder before any real launch
remains unresolved - see [DATA_SOURCES.md](DATA_SOURCES.md) "Production geocoder decision
record."

## Architected now, intentionally not built

These are structurally supported (interfaces, domain seams, nullable models) so they are
additive later rather than requiring rework, but are explicitly out of scope so far:

- Route-aware marine weather along a real charted course.
- Tidal-current effects on a planned route.
- Multi-day boating outlook (beyond a single day's hourly assessment).
- Fuel-range planning.
- Moon phase display (sunrise/sunset/civil twilight shipped in Sprint 4 - see
  [PLANNING.md](PLANNING.md)).
- Fishing-specific mode; paddle/PWC-specific profiles.
- Marine radar overlay.
- Lightning-proximity detection.
- USCG Local Notices to Mariners parsing/surfacing.
- Bridge-opening and lock information.
- Ramp crowd/status community reporting.
- Marina reservation integration.
- Fuel price and mooring/transient-slip pricing feeds.
- Offline saved trip briefings; shareable trip/weather briefings.
- Trip history and post-trip condition feedback (forecast-accuracy feedback loop).
- WorkManager background refresh and "check before your day" notifications.
- A map screen.

## Explicit non-goals

- WakeWindow is not, and will not become, a chartplotter or navigational instrument.
- WakeWindow will not fabricate a marine route by reusing road-routing output — a straight
  line between two ports routinely crosses land.
- WakeWindow will not invent marine data values when a provider has none. Missing beats
  wrong.
- WakeWindow will not build an uncontrolled web scraper against arbitrary marina/harbor
  websites for facility intelligence — see "Launch intelligence" above.
- No backend and no user accounts unless a real requirement emerges; all persistence is
  local (Room) for the foreseeable future.
