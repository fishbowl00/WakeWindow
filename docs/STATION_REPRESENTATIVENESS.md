# WakeWindow — Station Representativeness

Sprint 3's core question: **does WakeWindow understand where its evidence came from, and
whether that evidence actually represents the user's boating environment?** This document
specifies the two mechanisms that answer it — [`WaterEnvironment`](../app/src/main/java/com/wakewindow/app/domain/observation/WaterEnvironment.kt)
classification and [`StationRepresentativeness`](../app/src/main/java/com/wakewindow/app/domain/observation/StationRepresentativeness.kt)
scoring — and the forecast-vs-observation comparison they enable. See
[MARINE_SCORING.md](MARINE_SCORING.md) for how the result actually affects scoring, and
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md) for live proof against real launches.

## Why this exists

Sprint 2 compared a buoy's *observation* against the *launch's* forecast for the departure
hour. This conflates two different questions: "does the forecast agree with reality?" and "is
this station's reality even representative of the launch?" A buoy 23 NM offshore disagreeing
with an inshore harbor's forecast is not evidence the forecast is wrong — it's evidence the two
places have different weather, which is unsurprising and uninformative. Sprint 3 separates
these into two explicit, independently-computed facts.

## `WaterEnvironment` — where is this point, roughly?

A coarse, deliberately conservative classification — just enough to catch an obviously invalid
comparison, not a hydrographic survey:

```
INLAND | RIVER | ESTUARY | INTRACOASTAL | HARBOR | NEARSHORE | OFFSHORE | GREAT_LAKES | UNKNOWN
```

`WaterEnvironmentClassifier.classify(nwsPointType, nearestTideStationDistanceNm)` uses only
signals already fetched elsewhere in the pipeline - **no extra network call**:

| NWS point type | Tide station distance | Result |
|---|---|---|
| `marine` | (any) | `NEARSHORE` |
| `land` | ≤ 5 NM | `HARBOR` |
| `land` | 5–25 NM | `ESTUARY` |
| `land` | > 25 NM, or no station | `INLAND` |
| anything unresolvable | — | `UNKNOWN` |

`RIVER`, `INTRACOASTAL`, and `GREAT_LAKES` are real enum values used elsewhere in the model
(compatibility rules, evidence requirements) but this sprint's heuristic cannot yet distinguish
them from the categories above - they are recognized concepts, not yet reachable outcomes.
**`UNKNOWN` is the correct, honest result whenever the available signals don't support a
confident call** - never a guess. Confirmed live: Port Canaveral resolved to `HARBOR`, Clinton
Lake to `INLAND` - see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md).

`WaterEnvironmentClassifier.areCompatible(a, b)` is a coarse, symmetric, documented table (not
a distance formula): the same environment is always compatible with itself; `HARBOR`/
`ESTUARY`/`INTRACOASTAL`/`NEARSHORE` form a "coastal" cluster that's mutually compatible;
`NEARSHORE`/`OFFSHORE` form an "open water" cluster; `UNKNOWN` is compatible with nothing,
including itself - an unclassified pair is never assumed to match.

## `StationRepresentativeness` — how much should this station's reading count as evidence for *this* launch?

Distinct from [`ObservationFreshness`](DATA_SOURCES.md) (which only measures age).
`StationRepresentativenessEvaluator.evaluate(distanceNm, launchEnvironment, stationEnvironment,
freshness)`:

1. `freshness == UNUSABLE` → `LOW`, unconditionally - a >180-minute-old reading isn't
   representative of anything current, regardless of distance or environment.
2. Either environment `UNKNOWN` → `UNKNOWN` - never guess compatibility from an unclassified
   pair.
3. Environments not `areCompatible` → `LOW` - a station in a fundamentally different kind of
   water is not evidence for this launch no matter how close or fresh.
4. `distanceNm ≤ 10` and `freshness == FRESH` → `HIGH`.
5. `distanceNm ≤ 30` → `MEDIUM`.
6. Otherwise → `LOW`.

Every non-`HIGH` result carries a human-readable reason ("Station is 23 NM away and in a
compatible environment, but not close enough for full confidence"). This is what
`ObservationStationCard` shows in the UI, and what
[`ObservationalCautionEvaluator`](MARINE_SCORING.md) gates on before letting an observation
influence the assessment at all.

## `ObservationForecastComparison` — the comparison itself

Built by `DefaultBoatingRepository.buildObservationComparison()`, always against the **station's
own coordinates**, never the launch's:

1. Fetch a forecast for the station's location, in a ±2 hour window around the observation's
   own timestamp (not the whole trip - a handful of hours is all that's needed).
2. Classify the station's own `WaterEnvironment` the same way as the launch (NWS point type at
   the station's coordinates, plus a tide-station-distance lookup from the station's location).
3. Compute `StationRepresentativeness` from the launch/station environment pair, freshness, and
   distance.
4. Find the nearest available forecast hour to the observation's timestamp. If none is within
   90 minutes, the comparison is `ComparisonStatus.TIME_MISALIGNED` rather than comparing
   mismatched times; if no forecast-at-station could be resolved at all, it's
   `NO_FORECAST_AT_STATION`. Only a genuinely close match is `COMPARABLE`.
5. When `COMPARABLE`, run `MarineDisagreementDetector` between the forecast-at-station and the
   observation - the exact same per-field thresholds Sprint 2 used, just now comparing the
   right two things.
6. An `UNUSABLE`-freshness reading's disagreements are never surfaced as if they describe
   *current* conditions, even when a forecast value was technically found to compare against.

`ComparisonStatus.NOT_ATTEMPTED` exists as a documented possibility (no station/observation at
all) but in practice `DefaultBoatingRepository` represents that case as a `null`
`observationComparison` on `BoatingWindowAssessment`, exactly like `nearestObservationStation`
being `null` - consistent with the rest of the codebase's nullable-means-absent convention.

## How the comparison feeds scoring — never averaging

`ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel)` decides whether the
comparison should affect the assessment at all, and produces a `Hazard` (never a blended value)
when it does:

- Representativeness must be `HIGH` or `MEDIUM` - `LOW`/`UNKNOWN` never influence anything, no
  matter how alarming the observed reading is.
- The observation must be within 3 hours of the planned departure - a representative,
  materially-worse reading from six hours ago says nothing about departure conditions.
- Only a field that's genuinely *worse* than forecast counts (a better-than-forecast or
  unrelated-field difference, like temperature, is not a safety signal and never gates).
- The resulting `Hazard` caps the *departure point only*, at `POOR` if the observed value
  itself exceeds the vessel's own tolerance, `CAUTION` otherwise - applied via `worstCategory()`
  exactly like every other gate in `MarinePointScorer`, never as a fixed point deduction and
  never blended into the forecast series.

See [MARINE_SCORING.md](MARINE_SCORING.md) "Observation influence on assessment" for the
scoring-side mechanics, and [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md) for the live
Port Canaveral result (`MEDIUM` representativeness, `COMPARABLE` status, no disagreement found
on the day of testing - a genuinely different, more honest result than Sprint 2's launch-vs-buoy
comparison, not a contradiction of it).
