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

## Next sprint (highest-value follow-on)

1. **Vessel profile UI** — let users switch between a couple of built-in profiles (center
   console, pontoon, PWC) before investing in full custom profile editing.
2. **A real facility-intelligence source** for `MarineFacilityInfoProvider` — starting with one
   controlled, licensable source (see "Launch intelligence" below) rather than attempting broad
   coverage at once.
3. **`CurrentProvider` implementation** (NOAA CO-OPS current predictions/observations) — the
   interface exists; every confidence explanation currently lists "no local current station
   available" as a standing limitation.
4. A real caching layer around `DefaultBoatingRepository.buildAssessment()` (see "Scale and
   Provider Risk" below) — currently every call fans out to every provider fresh.
5. Expand automated test coverage around real-world edge cases as they're found on-device
   (DST transitions, provider outages during a live session, more inland/sparse-data
   locations beyond the one Kansas case tested so far).

## Launch intelligence — deliberately not a web scraper

`MarineFacilityInfoProvider` has zero implementations by design this sprint (see
[DATA_SOURCES.md](DATA_SOURCES.md) "Marine place / launch intelligence"). Scraping arbitrary
marina/harbor-authority websites is explicitly rejected as an approach: fragile against layout
changes, legally ambiguous (ToS, robots.txt, copyright on facility descriptions), and
unmaintainable once dozens/hundreds of sites are involved. Candidate controlled sources for a
future sprint, none yet evaluated for licensing terms or API availability:

- Official port/harbor-authority websites or APIs where one exists and terms permit reuse
  (`SourceType.OFFICIAL_PORT`).
- State boating-access-site datasets — several states publish structured ramp/facility data
  for public boat ramps (`SourceType.STATE_AGENCY`).
- USACE facility data for Corps-managed lakes and reservoirs (`SourceType.USACE`) — plausible
  for the Clinton Lake, KS class of location tested this sprint.
- A WakeWindow-curated dataset for a small number of high-traffic launches, manually verified
  and dated (`SourceType.USER_PROVIDED`/an internal curation source type), as a bootstrap
  before any automated source exists.

## Scale and Provider Risk

Not built yet — this section records what would need attention before real user growth, so
scale problems aren't discovered for the first time in production. All figures are estimates
based on the request pattern in `DefaultBoatingRepository.buildAssessment()`, which currently
makes 7-8 outbound calls per assessment (NWS grid, NWS alerts, Open-Meteo general, Open-Meteo
marine, CO-OPS station list, CO-OPS tide predictions, NDBC snapshot, NDBC station names) with
**no caching above the individual-provider level** — see "Caching" in
[ARCHITECTURE.md](ARCHITECTURE.md).

| Users (concurrent-ish daily actives) | Primary risk | Notes |
|---|---|---|
| ~500 | Photon (`photon.komoot.io`) | A shared public demo instance with no SLA; RideCast's own docs already flag this as a pre-beta P0. WakeWindow inherits the identical risk the moment more than a handful of people search launches concurrently. |
| ~1,000 | NDBC `latest_obs.txt` refetch rate | The 10-minute in-memory cache is per-process (per device), not shared — every device independently re-downloads the same ~100 KB network-wide file. At this scale it's still almost certainly fine for NDBC's own infrastructure, but there's no server-side layer to fall back on if it isn't. |
| ~5,000 | Duplicate/redundant API requests | Two people planning the same popular launch (e.g. Port Canaveral) at the same time each trigger a full independent fetch — no request de-duplication or shared cache exists across users, only RideCast-style per-coordinate caches scoped to a single app process. This is the most likely first real problem. |
| ~10,000 | NWS usage etiquette; CO-OPS station-list bandwidth | NWS has no hard published quota but does ask for good-citizen behavior (descriptive `User-Agent`, avoid unnecessary repeated calls) — at this volume WakeWindow should be able to show it isn't hammering `api.weather.gov` per-device with zero server-side aggregation. The CO-OPS ~3,500-station list (fetched once per process, ~2 MB) becomes a real aggregate bandwidth cost multiplied across many fresh app installs/updates. |

**What this sprint deliberately does not do about it:** build a backend, a shared cache
server, or request-coalescing infrastructure. That's real infrastructure work that isn't
justified before there's a real user base to serve — premature backend investment here would
be exactly the "architectural theatre" this roadmap's own opening paragraph warns against. The
concrete first fix, when it's warranted, is straightforward: a durable (Room-backed),
location-keyed cache with a TTL suited to each data type's real freshness needs (RideCast's own
three-tier decorator pattern is the template — see [ARCHITECTURE.md](ARCHITECTURE.md)
"Caching"), plus swapping Photon for a production-appropriate geocoder before any real launch.

## Architected now, intentionally not built

These are structurally supported (interfaces, domain seams, nullable models) so they are
additive later rather than requiring rework, but are explicitly out of scope so far:

- Port-to-port / trip planning (Mode B) beyond manual waypoints.
- Route-aware marine weather along a real charted course.
- Tidal-current effects on a planned route.
- Multi-day boating outlook (beyond a single day's hourly assessment).
- Saved custom vessel profiles beyond a couple of built-in presets.
- Fuel-range planning.
- Sunrise/sunset and moon phase display.
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
