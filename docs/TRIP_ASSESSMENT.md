# WakeWindow — Trip Assessment (Mode B, Sprint 5)

This is the companion to [TRIP_PLANNING.md](TRIP_PLANNING.md): that doc covers the trip *model*
(`MarineTripPlan`, waypoints, planning distance/ETA); this one covers what Sprint 5 added on top
of it - turning a trip plan into a real, timed, worst-case-gated assessment. See
[docs/ROADMAP.md](ROADMAP.md) Sprint 5 for the full change list.

## The core idea

Mode A (`DefaultBoatingRepository`) fetches one location's hourly timeline once and evaluates
every sample (departure/underway/return) against that single timeline. That's correct for Mode
A - the boat never leaves the vicinity of one launch. It is wrong for a trip: a waypoint two
hours and thirty nautical miles away has a *different* location and a *different* expected time
than departure, and evaluating it against departure's forecast would silently misrepresent
conditions the boater will actually experience there.

`DefaultTripBoatingRepository` (`data/repository/DefaultTripBoatingRepository.kt`) fixes this by
fetching **per trip point, at that point's own location and own expected arrival time** -
departure, every generated weather sample, every user waypoint, and the destination each get
their own independent fetch (general + marine forecast, alerts, tide, current, and - only when
applicable, see "Observation relevance" below - a buoy observation).

## Why trip scoring doesn't reuse `MarineScoreEngine` directly

`MarineScoreEngine.assess()` is deliberately not reused for Mode B, even though it already
accepts an arbitrary `List<RouteSample>`. Two of its design choices are specific to Mode A's
"depart, go do stuff, come back to the same place" shape and don't generalize to a one-way,
multi-leg trip:

1. Its blended score formula over-weights the **return** point specifically (35% of the blend)
   and its hazard-severity ranking scales by proximity to the return instant
   (`returnProximityWeight`). A trip has no "return" - the analogous concept (destination) isn't
   privileged the same way; a hazard near waypoint 2 of 4 matters exactly as much as one near
   the destination.
2. Its observational-caution application is hardcoded to `RouteSampleRole.DEPARTURE` only. A
   trip needs the equivalent evaluated **per point**, each against its own near-term-relevance
   window - see "Observation relevance" below.

Instead, `MarinePointScorer.score()` - the location-agnostic, per-point half of the scoring
pipeline `MarineScoreEngine` itself is built from - is reused directly, once per trip point.
`domain/trip/TripAssessmentBuilder.kt` is the new, small, pure combiner that replaces
`MarineScoreEngine`'s aggregation step: it worst-cases every point's category (never averages),
ranks hazards by severity alone (no return-proximity bias), and builds a deterministic
`mainConcern` sentence from the single worst hazard and the point it occurred at.

## Output shape

```kotlin
data class TripPointAssessment(
    val kind: TripPointKind,              // DEPARTURE, WEATHER_SAMPLE, WAYPOINT, DESTINATION
    val name: String?,                     // null only for a generated WEATHER_SAMPLE
    val point: PointAssessment,             // at, category, score, hazards, confidence - reused from Mode A
    val waterEnvironment: WaterEnvironment,
    val nearestTideStation: TideStation?,
    val nearestCurrentStation: CurrentStation?,
    val nearestObservationStation: SelectedMarineStation?,
    val observationApplicable: Boolean,
)

data class TripLegAssessment(
    val leg: TripLeg,
    val from: TripPointAssessment,
    val to: TripPointAssessment,
    val weatherSamples: List<TripPointAssessment>,
)

data class TripAssessment(
    val plan: MarineTripPlan,
    val timeline: List<TripPointAssessment>,   // strict chronological order - departure, ... , destination
    val legs: List<TripLegAssessment>,
    val overallCategory: BoatingCategory,       // worst-case across every point on `timeline`
    val worstHazards: List<Hazard>,
    val confidence: Confidence,                 // worst-of across every point
    val mainConcern: String?,                    // e.g. "Thunderstorm probability 62% near Sebastian Inlet"
    val horizonWarning: String?,                 // set when a point exceeded the forecast horizon
    val limitViolations: List<TripPlanLimitViolation>,
)
```

`TripPointKind` is deliberately distinct from `RouteSampleRole.WEATHER_SAMPLE` in *purpose*
(though `WEATHER_SAMPLE` is the underlying `RouteSample` role a generated sample carries): the
UI-facing question that matters is "did the user choose this point," and `WAYPOINT` vs
`WEATHER_SAMPLE` must never blur - see TRIP_PLANNING.md "Weather samples vs. planning
waypoints."

## Intermediate weather sampling

`domain/trip/WeatherSampleGenerator.kt` decides, per leg, how many additional weather-only
sample points to add between two user-supplied points:

- Legs under 15 NM: zero samples - the two endpoints already describe conditions at that scale.
- 15-40 NM: one sample.
- Beyond that: one additional sample per ~25 NM, capped at `TripPlanLimits.MAX_WEATHER_SAMPLES_PER_LEG`
  (3) - deliberately conservative, never continuous/blind oversampling.

Sample locations are interpolated along the great-circle line between the leg's endpoints
(`GeoPoint.interpolateTo`) - exactly as honest about non-navigability as `TripLeg.planningDistanceNm`
itself. Sample times are interpolated proportionally between the leg's own start time (the
previous point's arrival, or departure time for the first leg) and its own estimated arrival -
never the departure-hour forecast reused across the whole leg.

## Observation relevance (time- and location-aware)

A live buoy observation only ever describes *right now*. `MarineObservationProvider.nearestObservation()`
has no time parameter and structurally cannot answer "what will the buoy be reporting when the
boater reaches waypoint 3 four hours from now." `DefaultTripBoatingRepository` only fetches an
observation for a point whose expected arrival is within a 3-hour window of the current instant
(the same near-term window `ObservationalCautionEvaluator` already uses for Mode A's departure
point) - a departure ninety minutes from now gets a real observation comparison; a destination
three days out does not, and `TripPointAssessment.observationApplicable` is `false` for it with
`nearestObservationStation` left `null` rather than silently reusing a stale/irrelevant reading.
Live-validated (see "Live validation" below): a short real trip's departure point (within the
near-term window) resolved a real NDBC station and observation comparison; its destination
point one hour later, once just outside the window relative to wall-clock "now" at test time,
correctly had no observation applied.

## Tide/current relevance

Each point resolves its own nearest tide and current station independently - no single
"the" station is blindly attached to every waypoint. Live-validated: a real ~90 NM three-leg
trip (Port Canaveral → Sebastian Inlet → Fort Pierce Inlet) resolved three *different* real tide
stations (Trident Pier, Canova Beach, Sebastian Inlet bridge) and reused one real current
station (Fort Pierce Inlet Entrance) only where it was actually the nearest one, exactly as
CO-OPS's own station geography dictates - never a single reused station copied across every
point.

## Error resilience

Every trip point's fetch is independently wrapped (`fetchPointSafely`) so one point's provider
failure degrades that point to an honest `UNAVAILABLE` category with a specific "conditions
unavailable near X" confidence reason, without blocking or crashing the rest of the trip - see
`DefaultTripBoatingRepositoryTest`'s `"a provider failure at one waypoint reports that point
unavailable without blocking the rest of the trip"`. An `UNAVAILABLE` point's confidence is
`UNAVAILABLE`, and `Confidence.worstOf` propagates that to the whole trip's confidence - a
single gap is never hidden by averaging it away against points that did succeed.

## Forecast horizon

`TripPlanLimits.MAX_FORECAST_HORIZON` (7 days) bounds how far ahead a point's fetch is even
attempted. A point whose expected arrival falls beyond that horizon skips the network fetch
entirely and is scored `UNAVAILABLE` with an explicit "Marine forecast is not available this far
ahead yet" confidence reason; `TripAssessment.horizonWarning` is set whenever this happens to any
point. This never blocks building or saving the trip plan itself - only that point's own
assessment is honestly reported as unavailable rather than a fabricated forecast.

## Trip complexity limits

`domain/trip/MarineTripPlan.kt`'s `TripPlanLimits` object documents every ceiling in one place:

| Limit | Value | Rationale |
|---|---|---|
| Manual waypoints | 10 | Far beyond any real single- or multi-day recreational trip. |
| Weather samples per leg | 3 | See "Intermediate weather sampling" above. |
| Forecast horizon | 7 days | Beyond this, no configured provider has meaningful skill left. |
| Trip duration | 14 days | Longer is treated as pathological input, not a real recreational trip. |

`TripPlanLimits.validate(plan)` never throws or blocks - it returns a list of
`TripPlanLimitViolation`s that `TripAssessment.limitViolations` carries through to the UI.

## Timezone handling - a documented, honest limitation

`MarineTripPlan.zoneId` is resolved once, from the *departure* point's own coordinates (via
`NwsProviders.resolveZoneId`), exactly like Mode A's `BoatingPlan.zoneId`. A trip whose waypoints
or destination cross into a different time zone does **not** get a per-point zone resolution or
display this sprint - every point's time is rendered in the departure zone. This is a real,
acknowledged gap (see the sprint brief's Phase 21), not a silent bug: cross-timezone recreational
boating trips are rare enough at WakeWindow's current scope that a per-point zone lookup (one
more NWS `/points` call per point, plus UI work to show "arrival 2:15 PM ET" vs "11:15 AM PT" in
the same timeline) was deferred rather than rushed. `TripLegEstimator`'s own `Instant`-based
timing is unaffected by this - only display formatting would need the per-point zone.

## Not attempted this sprint

- **Alternative departure-window scan** (sprint brief Phase 13, explicitly marked a stretch
  goal) - "leaving 90 minutes earlier avoids the highest storm risk" - not built. The domain
  model (`TripAssessmentBuilder.build`, pure and deterministic) is structurally ready for a
  caller to invoke it repeatedly across candidate departure times and compare results, but no
  such scan/comparison UI or logic exists yet.
- **Per-point timezone resolution/display** - see above.
- **NDBC observation durable caching** - deliberately deferred; see CACHE_POLICY.md for the
  specific reason found this sprint (cached "age minutes" would go stale between the fetch and
  a later cache hit within the same short TTL window).

## Testing

- `WeatherSampleGeneratorTest`, `TripAssessmentBuilderTest`, `MarineTripPlanTest` (limits/id/
  duration additions) - pure domain logic, no network, no fakes beyond hand-built data.
- `DefaultTripBoatingRepositoryTest` - integration-level, hand-written fakes (matching
  `DefaultBoatingRepositoryTest`'s own philosophy): per-point timed weather, one waypoint's
  provider failure not blocking the rest of the trip, forecast-horizon honesty, time-aware
  observation relevance, and total provider failure still returning a result.
- **Live validation** (this sprint, real network, not part of the committed suite - see
  docs/ROADMAP.md Sprint 5 for the full account): a short real Florida coastal trip (Port
  Canaveral → Sebastian Inlet), a longer three-point real trip (Port Canaveral → Sebastian Inlet
  → Fort Pierce Inlet, ~90 NM, exercising a generated weather sample on each leg), and a real
  inland non-tidal lake trip (Clinton Lake, KS) confirming zero fabricated tide/current data for
  a water body that genuinely has none.
