# WakeWindow — Data Sources

Every provider is accessed through a domain-level interface
(`GeneralWeatherProvider`, `MarineForecastProvider`, `MarineObservationProvider`,
`TideProvider`, `CurrentProvider`, `MarineAlertProvider`, `MarinePlaceProvider` — see
[ARCHITECTURE.md](ARCHITECTURE.md)). Nothing in `domain/` or `ui/` references a specific
vendor. This document records what each concrete implementation actually does, verified
against live responses on 2026-08-30, not just vendor documentation — API behavior in
practice does not always match the docs, particularly for NWS marine coverage.

## National Weather Service (`api.weather.gov`)

**Role:** primary source for general weather (temperature, sky cover, precipitation
probability, thunderstorm probability), wind/gusts, alerts, and — with an important caveat
below — coastal/offshore marine numeric forecast data. Free, no API key, but **requires a
descriptive `User-Agent` header** (e.g. `WakeWindow/1.0 (contact@inknautlabs.example)`);
requests without one are rate-limited more aggressively and NWS explicitly asks for contact
info in case of abuse.

**License/attribution:** U.S. government public data, no license restrictions. Good practice
to attribute "Data: National Weather Service" and link back to `weather.gov`.

### Verified behavior: land vs. marine points differ structurally

`GET /points/{lat},{lon}` returns a `properties.type` field that is either `"land"` or
`"marine"` depending on where the point falls, and a different `forecastZone` (a `FLZ###`
land zone vs. an `AMZ###` marine zone). This is the first fork in the provider logic — the
same coordinate a boater picks as "underway" may resolve to a marine point even a mile or two
off a coastline that resolves to a land point.

Confirmed with two real points near Port Canaveral, FL:

| Point | `type` | `forecastZone` |
|---|---|---|
| `28.408,-80.591` (at the port, essentially on land) | `land` | `FLZ747` |
| `28.30,-80.30` (~16 NM offshore) | `marine` | `AMZ552` |

### Verified behavior: `/forecast` and `/forecast/hourly` do NOT work for marine points

For the offshore (`type: marine`) point, both
`GET /gridpoints/{office}/{x},{y}/forecast` and
`GET /gridpoints/{office}/{x},{y}/forecast/hourly` return **HTTP 404** with:

```json
{
  "title": "Marine Forecast Not Supported",
  "type": "https://api.weather.gov/problems/MarineForecastNotSupported",
  "status": 404,
  "detail": "Forecasts for marine areas are not yet supported by this API."
}
```

This is exactly the trap the product brief warned about: **do not assume the ordinary hourly
forecast endpoint behaves the same offshore.** A naive port of RideCast's NWS client (which
only ever calls `/forecast/hourly` for land-based commute points) would silently fail for
every truly offshore point.

Likewise, the zone-based JSON forecast endpoint —
`GET /zones/forecast/{zoneId}/forecast` — also returns the same 404
`MarineForecastNotSupported` for a marine zone (`AMZ552`), even though it works for land
zones. There is currently no structured JSON "marine zone forecast" endpoint in the public
API.

### What *does* work offshore: `forecastGridData`

`GET /gridpoints/{office}/{x},{y}` (the raw gridded numeric data endpoint,
`forecastGridData` in the `/points` response) **works for both land and marine grids**, and
for a marine grid it returns additional populated properties not meaningfully present on land
grids:

- `waveHeight`, `waveDirection`, `wavePeriod`
- `windWaveHeight`
- `primarySwellHeight`, `primarySwellDirection`
- `secondarySwellHeight`, `secondarySwellDirection`
- `twentyFootWindSpeed`, `twentyFootWindDirection` (marine wind reference height, not the
  10 m standard used on land)
- `probabilityOfTropicalStormWinds`, `probabilityOfHurricaneWinds`

(A land grid's schema technically includes a `waveHeight` key too — the gridpoints schema is
a fixed superset of properties across all offices — but it is empty/near-shore-irrelevant for
land grids; the values that matter are populated on marine-classified grids.)

**Consequence for `MarineForecastProvider` (NWS implementation):** it must call
`forecastGridData`, not `/forecast/hourly`, whenever the resolved point (or a route sample
along a trip) is offshore. Each `forecastGridData` property is a **time-series with
ISO-8601 interval `validTime` strings**, e.g.
`"validTime": "2026-08-30T18:00:00+00:00/PT6H", "value": 1.2` — a value that holds for a
6-hour span starting at that instant, not a simple per-hour array like `/forecast/hourly`
returns. Mapping this into hourly `MarineConditions` requires expanding those ISO-8601
duration intervals, which `/forecast/hourly`'s simpler `periods[]` array never required.
**Implementation note (as actually built):** `NwsProviders` implements both
`GeneralWeatherProvider` and `MarineForecastProvider` by calling `forecastGridData` exclusively
— it never calls `/forecast/hourly` at all, for land or marine points. This is a deliberate
simplification over the "prefer `/forecast/hourly` on land, fall back to `forecastGridData`
offshore" branching originally considered: since `forecastGridData` already works everywhere
and gives precise numeric data (vs. `/forecast/hourly`'s human-readable-text periods, which
would need free-text parsing for thunderstorm/condition classification — see RideCast's own
regex-based approach in [RIDECAST_REFERENCE_AUDIT.md](RIDECAST_REFERENCE_AUDIT.md)), one grid
fetch per location serves both provider roles with no land/marine branch to get wrong. The
`/forecast/hourly` endpoint remains available as a future option if a more human-readable
`shortForecast`-style condition string is ever wanted for display.

### Human-readable marine text forecasts live in a separate legacy feed

`GET /products/types/CWF/locations/{officeId}` lists **Coastal Waters Forecast** text
products (issued periodically per office, e.g. `KMLB`), retrievable as free-text bulletins
via `GET /products/{id}`. This is the same text a boater would read on NOAA weather radio or
`marine.weather.gov`. It is **not** structured JSON and requires its own text-bulletin parser
if surfaced. This is deliberately **out of MVP scope** (see [ROADMAP.md](ROADMAP.md)) — the
numeric `forecastGridData` fields above already give WakeWindow everything the scoring engine
needs, and parsing free-text marine bulletins well is a project of its own. The seam
(`MarineForecastProvider` could expose an optional `narrativeSummary: String?`) is left open,
not built.

### Alerts

`GET /alerts/active?point={lat},{lon}` returns active watches/warnings/advisories for a
point, including marine-specific products (Small Craft Advisory, Special Marine Warning,
Gale Warning) when they apply — but also non-marine products (Heat Advisory, Dense Fog
Advisory, Severe Thunderstorm Warning, etc.) whenever they're active for that location. This
is the source for `MarineAlertProvider`. WakeWindow's own classification
(`NwsMapper.classify()`) deliberately does not filter to a marine-only allowlist, but as of
Sprint 3 it also no longer treats every advisory-tier alert identically — see
[MARINE_SCORING.md](MARINE_SCORING.md) "Alert relevance model" for the full per-event-type
table (a Heat Advisory is a real, visible score deduction, not the same kind of category-
capping consequence as a Small Craft Advisory), confirmed live with a real Heat Advisory
during both Sprint 2 and Sprint 3 testing (see
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)).

## NOAA NDBC — National Data Buoy Center

**Role:** observational validation/context only — buoy and coastal station observations, never
a forecast. Free, no API key, plain-text fixed-width responses (not JSON). Implemented by
`NdbcObservationProvider` (`data/remote/ndbc/`).

**Verified format** (`GET https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt`): one
whitespace-separated row per currently-reporting station **network-wide** (hundreds of buoys
and coastal C-MAN stations in a single ~100 KB file), columns `STN LAT LON YYYY MM DD hh mm
WDIR WSPD GST WVHT DPD APD MWD PRES PTDY ATMP WTMP DEWP VIS TIDE`, with **`MM` used as the
literal missing-value marker** in place of any field a station doesn't report. `NdbcObservationParser`
treats `"MM"` as "field not available" and leaves the corresponding property `null`, never
`0` — confirmed against real stations: `41009` (a met buoy 20 NM off Cape Canaveral) reports
wind but no wave sensor; `41113` (a nearshore Waverider buoy) reports wave height/period but
no wind; a naive parser assuming every station has every field would be wrong for most rows in
this file. Malformed or short lines (a stray header repeat, a truncated row, a non-numeric
coordinate) are skipped individually rather than failing the whole parse.

Station **names** are not in `latest_obs.txt` (only bare IDs) — `NdbcStationDirectory` does a
best-effort second lookup against `GET https://www.ndbc.noaa.gov/data/stations/station_table.txt`
(a separate pipe-delimited file, cached 24h since names essentially never change) to resolve
e.g. `41009` → "CANAVERAL 20 NM East of Cape Canaveral, FL" for display; a name-lookup failure
falls back to the bare station ID rather than blocking the observation itself.

### NDBC station selection

`NdbcStationSelector.select()` is **not simply nearest**. Candidates within a 75 NM radius
(NDBC coverage is sparse enough offshore that "nearest" alone can still return something far
away — this cutoff is the "useful" distance, matching the product brief's "18 nm away" example
scale, not an arbitrary round number) are ranked by, in order: freshness tier, then sensor
capability (a station with both wind and wave data beats one with only one, beats one with
neither — a wind-only or wave-only station is still selected in preference to nothing), then
distance. A station reporting a timestamp in the future (clock skew) is rejected outright, not
trusted. Every selection carries a `selectionReason` string explaining the choice, and the full
[SelectedMarineStation] record (ID, name, distance, observed time, age, freshness, which
sensors it has) travels with the result for provenance display — e.g. "CANAVERAL 20 NM East of
Cape Canaveral, FL · 23 NM away · observed 41 min ago (fresh)," confirmed live.

### NDBC observation freshness policy

NDBC does not publish a single guaranteed reporting interval per station — standard
meteorological buoys typically report roughly hourly, some coastal (C-MAN) stations more often
(observed live: several stations reporting 8-30 minutes apart). Given that variability, the
policy is set with headroom above one normal cycle rather than pinned to an exact SLA:

| Tier | Age | Treatment |
|---|---|---|
| `FRESH` | ≤ 45 min | Used at full confidence weight; eligible for forecast/observation disagreement detection |
| `AGING` | 45–90 min | Has likely missed one expected report; still used, confidence capped at `MEDIUM` |
| `STALE` | 90–180 min | Multiple missed cycles; treated as historical context only — excluded from disagreement detection, confidence capped at `LOW`, but still shown for provenance ("last report N min ago") |
| `UNUSABLE` | > 180 min | Excluded from both scoring input and disagreement detection entirely; the station may still be *selected* (nothing closer/fresher exists) so its age is visible, but its reading is never presented as current |

A stale/unusable observation never masquerades as a current reading — see
[MARINE_SCORING.md](MARINE_SCORING.md) "Forecast vs. observation."

**License/attribution:** U.S. government public data.

## NOAA CO-OPS — Tides & Currents (`api.tidesandcurrents.noaa.gov`)

**Role:** tide predictions, water levels, and (at PORTS-equipped stations) current
predictions/observations. Free, no API key.

**Verified behavior:**

- `GET /api/prod/datagetter?product=predictions&station={id}&date=today&datum=MLLW&units=english&time_zone=lst_ldt&format=json&interval=hilo`
  returns just the high/low events for the day, e.g.
  `{"predictions":[{"t":"2026-08-30 03:32","v":"0.235","type":"L"}, ...]}` — `type` is `H`
  or `L`, `t` is local time in the requested `time_zone`, `v` is height in the requested
  `units`/`datum`. **`time_zone` must be exactly `gmt`, `lst`, or `lst_ldt`** — a plausible
  guess like `lst_ld` is rejected with an explicit `Wrong Time Zone` error, which is easy to
  hit if the parameter is hand-typed instead of using an enum.
- `GET /mdapi/prod/webapi/stations/{id}.json` returns station metadata: name, lat/lng,
  timezone, `tideType` (e.g. `Mixed`), and whether the station is `tidal`. This is the
  correct source for `NearestTideStation` metadata (station ID, name, distance, datum
  available).
- Current stations are a **separate station list** from tide stations (`currents` product vs.
  `predictions`/`water_level`) — the nearest tide station and the nearest current station for
  a given launch are frequently not the same physical station, matching the product brief's
  requirement to track `NearestTideStation` and `NearestCurrentStation` independently.

**Consequence:** tide/current data must always be labeled with the source station's name and
distance from the plan location; it must never be presented as if it were measured exactly at
the user's ramp when the nearest station is materially distant (a threshold worth encoding
explicitly in confidence calculation, not left implicit). `CoopsTideProvider` enforces a 150 NM
cutoff — a station farther than that is treated as `TideStationOutcome.NotTidal` rather than a
distant-but-technically-found station, since CO-OPS's ~3,500-station tide-prediction list has
gaps large enough (interior lakes, some stretches of coastline) that "nearest" alone can still
return something not meaningfully representative of the query point.

**License/attribution:** U.S. government public data.

### Current predictions — implemented Sprint 3, verified live 2026-08-30

`GET /mdapi/prod/webapi/stations.json?type=currentpredictions` returns ~4,400 current-station
entries. **Most are harmonic (subordinate) stations** (`"type": "H"` in the metadata) with no
physical sensor - they predict the moments current *turns*, not a continuous speed curve. A
station with multiple depth bins appears once per bin (same `id`, different `currbin`) -
`CoopsCurrentProvider` dedups by keeping each id's first-listed bin, which is empirically
CO-OPS' own default for a bin-less predictions query (verified against station `FPI0901`: the
first-listed bin, 9, is exactly what a query with no `bin` parameter returns).

`GET /api/prod/datagetter?product=currents_predictions&station={id}&interval=MAX_SLACK` returns
the flood-max/ebb-max/slack cycle for the day, e.g.:

```json
{"current_predictions":{"units":"feet, knots","cp":[
  {"Type":"flood","meanFloodDir":258,"Bin":"9","meanEbbDir":81,"Time":"2026-08-30 00:56","Depth":"6","Velocity_Major":3.14},
  {"Type":"slack","meanFloodDir":258,"Bin":"9","meanEbbDir":81,"Time":"2026-08-30 04:05","Depth":"6","Velocity_Major":0},
  {"Type":"ebb","meanFloodDir":258,"Bin":"9","meanEbbDir":81,"Time":"2026-08-30 06:51","Depth":"6","Velocity_Major":-3.51}
]}}
```

`Velocity_Major` is **signed** (positive = flood, negative = ebb, ~0 = slack) - the domain
`CurrentEvent.speedKts` is always a magnitude, with direction instead coming from whichever of
`meanFloodDir`/`meanEbbDir` applies, and `null` at slack (direction is genuinely undefined at
zero velocity). Requesting a continuous interval (e.g. `interval=h`, or no interval at all) on
a harmonic station does **not** return a dense time series - it silently returns the same
MAX_SLACK-style event data, confirming these stations don't support anything finer. `interval=
MAX_SLACK` is requested explicitly rather than relying on that default, so the behavior doesn't
depend on an undocumented fallback.

`CurrentTimeline` (mirroring `TideTimeline`) interpolates a continuous per-hour speed/direction
from these discrete turns using the same cosine-bell approximation `TideTimeline` uses between
tide extremes - a reasonable approximation, not a claim of precision the underlying prediction
doesn't have.

**Currents are hyper-local in a way tide height is not.** `CoopsCurrentProvider` enforces a
50 NM cutoff (vs. 150 NM for tide) - a channel/inlet current reading 50+ NM away says nothing
useful about the current running at a different inlet. Confirmed live: the nearest CO-OPS
current station to Port Canaveral (`28.416056, -80.6078268`) is `FPI0901` (Fort Pierce Inlet),
**~58 NM away** - beyond the cutoff, so Port Canaveral correctly reports "No current station
within range" rather than presenting a Fort Pierce reading as if it applied locally. See
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md).

**Known limitation, stated honestly:** this implements current *predictions* only. A small
subset of CO-OPS stations (PORTS-equipped) also publish real-time current *observations* via a
separate `product=currents`; `CoopsCurrentProvider` does not yet distinguish or query these -
a scoped follow-up, not attempted this sprint given the much larger prediction-station coverage
already gained.

## Open-Meteo (`api.open-meteo.com`, `marine-api.open-meteo.com`)

**Role during development:** convenient supplementary source for general forecast fields
(already proven in RideCast) and, via the separate Marine API, wave/swell/current fields
useful where NWS marine `forecastGridData` coverage is thin or for cross-checking during
development — hidden entirely behind the `MarineForecastProvider`/`GeneralWeatherProvider`
seam.

**Verified fields available from the free Marine API:** `wave_height`, `wave_direction`,
`wave_period`, `wind_wave_height`, `swell_wave_height`, `swell_wave_direction`,
`swell_wave_period`, `sea_surface_temperature`, `ocean_current_velocity` (km/h — needs
conversion to knots), `ocean_current_direction`, all as hourly arrays keyed by ISO-8601
timestamp — a much simpler shape to map than NWS's interval-based `forecastGridData`.

**Commercial licensing constraint — explicit and important:** the free Open-Meteo endpoints
used above are documented by Open-Meteo as intended for non-commercial/development use; their
terms require a paid commercial license for production commercial use beyond the free-tier
call volume. **WakeWindow must never be structurally dependent on the free tier.** Concretely:

- Open-Meteo is wired in only as one interchangeable implementation of
  `MarineForecastProvider`/`GeneralWeatherProvider`, selected by DI configuration, never
  referenced directly from `domain/` or `ui/`.
- No Open-Meteo API key or credential is ever committed to the repository.
- Before any commercial release, this must be replaced or supplemented with one of: (a) an
  Open-Meteo commercial license, (b) another commercially-compatible marine data vendor, or
  (c) a fuller NOAA/open-government-data implementation (expanding the NWS `forecastGridData`
  path above, which has no such restriction).

**License/attribution:** CC BY 4.0 for the free tier — attribution ("Weather data by
Open-Meteo.com") required; see the commercial constraint above for production use.

## Marine place / launch intelligence — discovery vs. verified facility data

No single free API supplies both "where is this marina" and "what's its ramp fee, VHF
channel, and gate hours." This is kept as two architecturally separate concerns:
`MarinePlaceProvider` (discovery — name/address/coordinates/type, with honest provenance) and
`MarineFacilityInfo` (a provenance-tracked record with an explicit `FacilityAvailability`
state — `AVAILABLE`/`NOT_AVAILABLE`/`UNKNOWN`/`NOT_APPLICABLE` — per amenity field, so "we
don't know" is never conflated with "no"). See [PRODUCT.md](PRODUCT.md) "Facility data
states" and [ARCHITECTURE.md](ARCHITECTURE.md) for the full model.

**Discovery gained two real, boating-relevant sources this sprint** — the Florida FWC boat ramp
inventory and USACE recreation-area data, fanned out and ranked ahead of Photon by
`CompositeMarinePlaceProvider`. See [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md) for the full
design, ranking, and live-validated results.

### Florida FWC Boat Ramp Inventory (`gis.myfwc.com`)

**Verified live 2026-08-30.** ArcGIS REST `MapServer` layer 4
(`Open_Data/FWC_Florida_Boat_Ramp_Inventory`). The layer's own `Shape` geometry is in a
Florida-specific projected CRS (`wkid 102967`) — `Latitude`/`Longitude` **attribute fields** are
used directly instead (`returnGeometry=false`), avoiding any reprojection. Fields used:
`RampName`, `City`, `County`, `Latitude`, `Longitude`, `WaterBodyName`, `Status`, `RampType`,
`AccessType`, `Street1`. `Status` values observed live include `"Open for Business"` and
`"Temporarily Closed"` — a real, current fact about ramp availability that `FwcMapper` filters
on (see [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md)) rather than surfacing indiscriminately.
Florida-only coverage — a structural fact about the source, not a bug.

**License/attribution:** U.S./Florida state government public data, funded in part by a
U.S. Fish and Wildlife Service Federal Aid grant per the layer's own metadata. No published
rate limit encountered.

### USACE recreation areas (`services7.arcgis.com`)

**Verified live 2026-08-30.** A hosted ArcGIS FeatureServer
(`n1YM8pTrFmm7L4hs/.../usace_recreation_areas/FeatureServer/0`), `esriGeometryPolygon` — land
parcels around Corps reservoirs, described by USACE's own metadata as "Land associated with
Corps reservoirs used for recreational purposes." `returnCentroid=true` asks ArcGIS to compute
a representative point per polygon server-side. Fields used: `FEATURENAME` (often null in
practice), `RECPROJECTSITENAME` (reliably populated, e.g. `"TABLE ROCK LAKE"`),
`MANAGINGAGENCY`, `DISTRICT`. **This is not a boat-ramp inventory** — no field asserts a ramp
exists at a given parcel, confirmed by searching live data for ramp/launch-named features and
finding none; see [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md) for why `UsaceMapper` never claims
`MarinePlaceType.BOAT_RAMP` from it.

**License/attribution:** U.S. government public data (owner `usace_crrel_als` on ArcGIS
Online). No published rate limit encountered.

### Sprint 4 — `FwcFacilityInfoProvider`, the first real `MarineFacilityInfoProvider`

FWC's own boat ramp inventory already carried richer per-ramp fields than Sprint 3's
`FwcMapper` mapped through to `MarinePlaceCandidate`. Sprint 4 wires `OBJECTID`, `TotalLanes`,
`Amenities`, and `ContactPhone` through into a real `MarineFacilityInfo` record
(`FwcMapper.toFacilityInfo`), alongside the previously-fetched-but-discarded `RampType` and
`AccessType`, and the already-used `WaterBodyName`/`Status`:

- `TotalLanes` → `MarineFacilityInfo.rampLanes` (`Int?`)
- `ContactPhone` → `phone`
- `RampType`/`AccessType` → `rampType`/`accessType` (kept as the source's own free-text values,
  not mapped into a WakeWindow-invented enum, since the real value vocabulary isn't itself
  verified this sprint — see the caveat below)
- `Amenities` → `amenitiesRaw` (kept as raw free text for the same reason — the field's
  delimiter/format isn't verified enough to parse into individual `FacilityAvailability`
  booleans without risking a wrong guess)
- `WaterBodyName` → `waterBodyName`
- `Status` → both a classified `FacilityOperationalStatus` (`OPEN`/`CLOSED`/`PARTIALLY_OPEN`/
  `SEASONAL`/`UNKNOWN`, via `FwcMapper.operationalStatusOf` — confirmed live values from
  Sprint 3 are `"Open for Business"`/`"Temporarily Closed"`; anything unrecognized stays
  `UNKNOWN` rather than guessed) and the raw source string (`operationalStatusRaw`)
- `OBJECTID` → `MarinePlaceCandidate.sourceId` and `SourceReference.recordId` — lets
  `FwcFacilityInfoProvider` re-fetch the *exact* source record by ID (`OBJECTID=<id>`) rather
  than re-matching fuzzily by name, and falls back to an exact ramp-name match for a candidate
  saved before `sourceId` was tracked.

Every field FWC doesn't publish (hours, fees, gate hours, VHF channel, dock/fuel/restroom/etc.)
stays at its honest `UNKNOWN`/null default — this dataset simply doesn't carry those as
separate structured fields. `MarineFacilityInfo` also gained `waterBodyName`, `rampType`,
`accessType`, `amenitiesRaw`, `operationalStatus`, `operationalStatusRaw` this sprint to hold
the above.

Results are cached durably (`DurableCache`, 7-day TTL) — see [CACHE_POLICY.md](CACHE_POLICY.md).
Only a genuinely successful lookup is cached; "no data" and "failed" outcomes are always
re-attempted next time.

**Verification caveat, stated honestly:** this session's outbound network access does not
reach `gis.myfwc.com` (or any other live provider host — see the sprint report), so the three
newly-added field names (`TotalLanes`, `Amenities`, `ContactPhone`) could not be re-verified
live against the current schema the way the original fields were on 2026-08-30. They were
already identified as present in this exact layer during Sprint 3's discovery work (see
[ROADMAP.md](ROADMAP.md) "Next sprint" / [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md) "What this is
not," both written from a session that did have live access) — this sprint acts on that
existing, previously-verified finding rather than a fresh guess. If any of the three field
names is wrong, the JSON converter (`ignoreUnknownKeys = true`) simply returns `null` for it —
degrading to `UNKNOWN`/absent, never a fabricated value. Re-verifying live against the current
schema, and re-confirming `Amenities`' actual delimiter format (to decide whether it's ever
safe to parse into structured booleans), is a concrete next step once network access allows it.

**Still not attempted, deliberately:** USACE facility-level data (the recreation-areas layer
integrated for discovery is confirmed, not re-investigated this sprint, to carry no ramp/
facility fields — see [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md)) and any general-purpose web
scraper, per the same reasoning as Sprint 3. Other plausible future sources, still not
evaluated: official port/harbor-authority sites (`SourceType.OFFICIAL_PORT`) and
marina-operator-published data where a marina explicitly opts in
(`SourceType.MARINA_OPERATOR`). Each would need its own licensing/ToS review before
integration.

### Production geocoder decision record (Sprint 4)

The sprint brief asked for an investigation into a production-safe replacement for Photon as
the geocoding fallback. **This session's environment has no outbound network access to any
provider host** (see the sprint report's network-access section), which rules out the kind of
real evaluation this needs — checking a vendor's actual current pricing, ToS, coverage, and
Android SDK/REST ergonomics against real requests. Committing to a paid vendor without that
verification, or without the user's explicit sign-off on a cost-bearing dependency, would be
exactly the kind of decision the sprint brief itself warns against ("do NOT commit to a paid
vendor merely because it is easy... do NOT turn this into procurement research"). Decision:
**Photon remains the development/fallback geocoder, unchanged, with the risk it already
carries.** See "Provider licensing summary" below for the specifics of that risk and
[ROADMAP.md](ROADMAP.md) "Scale and Provider Risk" for when it becomes a real production
blocker (roughly the ~500-daily-active mark). Candidates worth evaluating once network access
and a cost decision are both available: Geoapify, LocationIQ, and Mapbox Geocoding all publish
free tiers with defined commercial terms as of general public information available at
authoring time — none of this has been verified against their current live terms this sprint,
and that verification is the actual next step, not a conclusion reached here.

## Provider licensing summary

| Provider | Data | Public/open govt. data? | Attribution required? | Rate-limited? | Commercially usable as configured? |
|---|---|---|---|---|---|
| NWS (`api.weather.gov`) | General + marine forecast, alerts | Yes | No (courtesy `User-Agent` requested, not legally required) | Soft — descriptive `User-Agent` requested, no published hard quota | Yes |
| NOAA NDBC | Buoy/station observations | Yes | No | No published quota; the `latest_obs.txt` snapshot is fetched and cached client-side (10 min TTL) specifically to avoid hammering it | Yes |
| NOAA CO-OPS | Tide + current predictions, station metadata | Yes | No | No published quota for this volume of use | Yes |
| Florida FWC | Boat ramp inventory (place discovery) | Yes | No | No published quota encountered | Yes — but Florida-only coverage, a structural not a licensing limit |
| USACE | Recreation-area land parcels (place discovery) | Yes | No | No published quota encountered | Yes |
| Photon (`photon.komoot.io`) | Place search/geocoding | No — public demo instance of an open-source project, not government data | Attribution to OpenStreetMap contributors is good practice | Yes — a shared public demo instance with no documented SLA; RideCast's own docs flag this same instance as a P0 risk before any public beta (see [RIDECAST_REFERENCE_AUDIT.md](RIDECAST_REFERENCE_AUDIT.md)) | **No** — a shared free demo instance is not an appropriate production dependency at any real user volume; see "Scale and Provider Risk" in [ROADMAP.md](ROADMAP.md) |
| Open-Meteo (general + Marine) | Supplementary general/marine forecast | No — a commercial product with a free tier | Yes, on the free tier (CC BY 4.0) | Yes, documented free-tier volume limits | **No** — free tier is documented as non-commercial/development use; see below |

No conclusion above goes beyond what each provider's own published documentation states: this
is a summary of stated terms, not independent legal advice, and the two "No" rows should be
revisited with real licensing/contractual review before any commercial release rather than
treated as a permanent architectural fact.

### Remove structural Open-Meteo dependency

WakeWindow must keep working with Open-Meteo disabled entirely, not merely "degraded" — see
"What this sprint deliberately does not do about it" above and the non-goal stated repeatedly
across this doc. `AppDependencies.boatingRepository(includeOpenMeteo: Boolean = true)` is the
seam: passing `false` builds `DefaultBoatingRepository` with only `nwsProviders` in both the
general and marine provider lists. This isn't a config value nothing reads — every existing
`DefaultBoatingRepositoryTest` case already runs exactly one general and one marine fake
provider, which is structurally identical to this flag set to `false`; that suite is the
regression coverage proving the NWS-only shape produces a real, non-crashing assessment (see
e.g. `total marine provider failure still produces a result instead of crashing`). The default
stays `true` (today's development configuration, unchanged) — flipping the real app's default
before production is a deliberate follow-up decision, not made here.

## Fallback behavior (cross-cutting)

- If a preferred provider fails or times out, the repository layer surfaces a
  provider-specific failure to the scoring engine rather than silently substituting a
  different provider's numbers without labeling the source change.
- **A failed check is never presented as a successful check that found nothing.** This was a
  real Sprint 1 gap, fixed in Sprint 2: a failed marine-alert fetch used to be indistinguishable
  from "checked, zero alerts active." It now downgrades confidence explicitly
  ("Marine alert status could not be verified") rather than silently reading as a clean bill of
  health — see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md) "Missing data policy" for
  the full audit of this class of bug.
- Every value that reaches the UI is paired with a `source` and, where applicable, an
  `observationAge`/`confidence` — see [MARINE_SCORING.md](MARINE_SCORING.md).
- No provider failure is allowed to crash the assessment for the whole plan — a missing
  marine layer degrades the confidence of the overall `BoatingAssessment` rather than
  producing no result at all (see the inland-lake case in [ROADMAP.md](ROADMAP.md), and the
  live Clinton Lake, KS validation in [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)).
