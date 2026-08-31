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
(`NwsMapper.classify()`) deliberately does not filter to a marine-only allowlist — see
[MARINE_SCORING.md](MARINE_SCORING.md) "Gates" for why an unrecognized advisory-tier alert
still gates the assessment rather than being silently ignored, confirmed live with a real
Heat Advisory during Sprint 2 testing (see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)).

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
`MarinePlaceProvider` (Photon, discovery only — name/address/coordinates/guessed type) and
`MarineFacilityInfo` (a provenance-tracked record with an explicit `FacilityAvailability`
state — `AVAILABLE`/`NOT_AVAILABLE`/`UNKNOWN`/`NOT_APPLICABLE` — per amenity field, so "we
don't know" is never conflated with "no"). See [PRODUCT.md](PRODUCT.md) "Facility data
states" and [ARCHITECTURE.md](ARCHITECTURE.md) for the full model.

**No facility-intelligence provider ships this sprint, deliberately.** `MarineFacilityInfoProvider`
is a defined interface with zero implementations — per the sprint brief, an uncontrolled web
scraper against arbitrary marina/harbor websites is explicitly out of scope (fragile, legally
murky, unmaintainable at any scale). `LaunchInfoScreen` is built to render `MarineFacilityInfo`'s
all-`UNKNOWN`/all-null default state honestly (confirmed live — see
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)), so wiring in a real source later is
additive, not a UI rewrite. Plausible future controlled sources, not yet evaluated in depth:
official port/harbor-authority sites (`SourceType.OFFICIAL_PORT`), state boating-access agency
datasets (`SourceType.STATE_AGENCY`), USACE lake/reservoir facility data
(`SourceType.USACE`) for Corps-managed lakes, and marina-operator-published data where a
marina explicitly opts in (`SourceType.MARINA_OPERATOR`). Each would need its own licensing/ToS
review before integration — none is assumed usable yet.

## Provider licensing summary

| Provider | Data | Public/open govt. data? | Attribution required? | Rate-limited? | Commercially usable as configured? |
|---|---|---|---|---|---|
| NWS (`api.weather.gov`) | General + marine forecast, alerts | Yes | No (courtesy `User-Agent` requested, not legally required) | Soft — descriptive `User-Agent` requested, no published hard quota | Yes |
| NOAA NDBC | Buoy/station observations | Yes | No | No published quota; the `latest_obs.txt` snapshot is fetched and cached client-side (10 min TTL) specifically to avoid hammering it | Yes |
| NOAA CO-OPS | Tide predictions, station metadata | Yes | No | No published quota for this volume of use | Yes |
| Photon (`photon.komoot.io`) | Place search/geocoding | No — public demo instance of an open-source project, not government data | Attribution to OpenStreetMap contributors is good practice | Yes — a shared public demo instance with no documented SLA; RideCast's own docs flag this same instance as a P0 risk before any public beta (see [RIDECAST_REFERENCE_AUDIT.md](RIDECAST_REFERENCE_AUDIT.md)) | **No** — a shared free demo instance is not an appropriate production dependency at any real user volume; see "Scale and Provider Risk" in [ROADMAP.md](ROADMAP.md) |
| Open-Meteo (general + Marine) | Supplementary general/marine forecast | No — a commercial product with a free tier | Yes, on the free tier (CC BY 4.0) | Yes, documented free-tier volume limits | **No** — free tier is documented as non-commercial/development use; see below |

No conclusion above goes beyond what each provider's own published documentation states: this
is a summary of stated terms, not independent legal advice, and the two "No" rows should be
revisited with real licensing/contractual review before any commercial release rather than
treated as a permanent architectural fact.

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
