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

## Sprint 4 — productization: launch intelligence, vessel personalization, planning UX, scale hardening (complete, with honest gaps)

Goal: move WakeWindow from "excellent technical prototype" toward something a real boat owner
would trust on a Saturday morning. **This sprint ran in an online Claude Code environment with
no outbound network access to any provider host or to Android's/Google's Maven repository** —
see the sprint report for the full account. No build, test run, or live provider validation
could be performed; every change below is implemented and reasoned through at the source level,
not verified by a green build. That verification is the mandatory first step of whatever comes
next. Shipped (pending that verification):

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

**Not attempted, or only partially, and why:** device UX validation (no physical device/
emulator in this environment — see the sprint report); USACE facility-data re-investigation (no
network access to inspect further live services); a full "GOOD TODAY" Home-screen condition
badge per saved launch (would need either durable serialization of the full assessment graph or
a user-triggered-refresh design — descoped to the lighter "usually 7 AM · 6h" recall instead,
which needed neither); broad accessibility/UI-polish audit beyond the screens directly touched
this sprint.

## Next sprint (highest-value follow-on)

1. **Get a real build and test run.** Nothing above has been compiled or executed this sprint —
   see the sprint report. This is the mandatory first step before any of the following.
2. **Live provider validation** — Port Canaveral, a second Florida ramp-rich area, Clinton Lake
   KS, and a sparse-data location, re-run against everything Sprint 4 touched (FWC facility
   fields especially — the three newly-added field names were never re-verified live).
3. **Physical-device / emulator UX pass** — the original Sprint 4 brief's first task (a Razr
   Ultra device audit) never happened; do it before further UI work, not after.
4. Wire `TripLegEstimator.routeSamples()` into a real per-location weather fetch and build a
   minimal Mode B screen once the domain layer has been validated against a real build.
5. Extend `DurableCache` to the weather/tide/current fan-out per the TTL table already
   documented in [CACHE_POLICY.md](CACHE_POLICY.md), with alerts getting their own careful
   staleness review first.
6. Real-time current *observations* (PORTS-equipped stations), distinct from the
   predictions-only implementation shipped in Sprint 3.
7. A genuine paid-geocoder evaluation (Geoapify/LocationIQ/Mapbox or similar) once network
   access allows checking real current terms, plus the user's sign-off on any cost-bearing
   dependency.

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
observation comparison when a station is available) with **no caching above the
individual-provider level** — see "Caching" in [ARCHITECTURE.md](ARCHITECTURE.md).

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

- Port-to-port / trip planning (Mode B) UI, and weather fetched per-waypoint - the domain
  model itself (`MarineTripPlan`, manual waypoints, planning-distance/ETA estimation) shipped
  in Sprint 4; see [TRIP_PLANNING.md](TRIP_PLANNING.md) for exactly what's still missing.
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
