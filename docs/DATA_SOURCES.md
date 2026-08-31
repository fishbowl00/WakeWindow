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
`GeneralWeatherProvider` (land-oriented fields: temperature, PoP, sky cover) can still prefer
`/forecast/hourly` when the point is a land point, since that endpoint is simpler and reads
better for human display; it should fall back to reading the same fields out of
`forecastGridData` for marine points where `/forecast/hourly` 404s.

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
Gale Warning) when they apply. This is the intended source for `MarineAlertProvider`.

## NOAA NDBC — National Data Buoy Center

**Role:** observational validation/context only — buoy and coastal station observations, never
a forecast. Free, no API key, plain-text fixed-width responses (not JSON).

**Verified format** (`GET https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt`, and the
per-station `GET https://www.ndbc.noaa.gov/data/realtime2/{station}.txt`): whitespace-aligned
columns (`WDIR WSPD GST WVHT DPD APD MWD PRES ATMP WTMP DEWP VIS TIDE`, etc.) with **`MM` used
as the literal missing-value marker** in place of any field a station doesn't report. A naive
parser that tries to coerce every column to a number will crash or silently produce `0.0` for
missing data — the mapper must treat `"MM"` as "field not available" and leave the
corresponding `MarineConditions` property `null`, never `0`.

**Consequence for `MarineObservationProvider`:** station selection must be distance-aware.
NDBC stations are sparse and often tens of miles offshore or apart; a naive "nearest station"
match can return a buoy 60–70 NM away. Every observation surfaced in the UI must carry its
source station ID, name, distance from the query point, and observation age, and the UI must
visibly flag both "this is an observation, not a forecast" and "this station is N miles away"
rather than implying the reading applies at the user's exact launch point.

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
explicitly in confidence calculation, not left implicit).

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
channel, and gate hours." This sprint deliberately keeps those two concerns architecturally
separate (`MarinePlaceProvider` for discovery/geocoding vs. a provenance-tracked
`MarinePlace` facility record — see [ARCHITECTURE.md](ARCHITECTURE.md) and
[MARINE_SCORING.md](MARINE_SCORING.md) are not the place for this, see the `MarinePlace`
section of `ARCHITECTURE.md`). RideCast's existing Geoapify/Photon geocoding integration is a
reasonable starting point for place *discovery* (name/address/coordinates), reused behind a
`MarinePlacePlaceholder` — but every authoritative operational field (fees, hours, VHF
channel, restrictions) is out of scope for automated discovery this sprint and is modeled as
explicitly absent (`"Not available"`) rather than guessed, each with a `SourceReference` seam
ready for when a verified data source is chosen.

## Fallback behavior (cross-cutting)

- If a preferred provider fails or times out, the repository layer surfaces a
  provider-specific failure to the scoring engine rather than silently substituting a
  different provider's numbers without labeling the source change.
- Every value that reaches the UI is paired with a `source` and, where applicable, an
  `observationAge`/`confidence` — see [MARINE_SCORING.md](MARINE_SCORING.md).
- No provider failure is allowed to crash the assessment for the whole plan — a missing
  marine layer degrades the confidence of the overall `BoatingAssessment` rather than
  producing no result at all (see the inland-lake case in [ROADMAP.md](ROADMAP.md)).
