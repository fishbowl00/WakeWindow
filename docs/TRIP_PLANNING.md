# WakeWindow — Trip Planning (Mode B)

Mode A (`BoatingPlan`) is a single day, out and back to the same launch — see
[PRODUCT.md](PRODUCT.md). Mode B is a port-to-port / multi-waypoint trip. Sprint 4 lays the
domain foundation only — see "What's built" and "What's explicitly not built" below before
assuming more exists than does.

## What "planning waypoint" means

A `PlanningWaypoint` (`domain/trip/MarineTripPlan.kt`) is the user's own claim about where they
intend to be — a name and a coordinate, optionally a manually-typed expected arrival. It is
**never** called a "navigation waypoint" anywhere in code, docs, or (once a UI exists) on
screen. WakeWindow does not verify a safe course exists between two consecutive waypoints, does
not know the local waterway, and does not account for land, shoals, channels, or charted
hazards. This is the same non-goal already stated in [ROADMAP.md](ROADMAP.md): *"WakeWindow
will not fabricate a marine route by reusing road-routing output — a straight line between two
ports routinely crosses land."* `TripLeg.planningDistanceNm` is named the way it is for exactly
this reason: a geodesic distance between two points the user chose, not a certified navigable
route length.

## Model

- `MarineTripPlan`: `departure`/`destination` (each a `PlanningWaypoint`), `departureTime`,
  `vessel`, `zoneId`, an ordered `waypoints` list, an optional `cruiseSpeedKts`, `notes`.
  `orderedPoints` is departure, then waypoints in the order given, then destination.
- `TripLegEstimator.estimateLegs(plan)`: one `TripLeg` per consecutive pair of points, each
  carrying the geodesic `planningDistanceNm` and an `estimatedArrival`. A waypoint's own manual
  arrival always wins; otherwise arrival is computed from the previous point's time plus
  `cruiseSpeedKts`. With **neither** a manual arrival **nor** a usable cruise speed for a leg,
  its arrival simply repeats the previous point's time — an honest, explicitly-flagged
  (`TripLeg.isResolved = false`) placeholder, never a fabricated ETA.
- `TripLegEstimator.routeSamples(plan)`: turns the plan into `RouteSample`s (`DEPARTURE`,
  `WAYPOINT` for each intermediate point, `DESTINATION`) — the same `RouteSample`/
  `RouteSampleRole` type `BoatingPlan.defaultRouteSamples()` already produces for Mode A
  (`RouteSampleRole.WAYPOINT`/`DESTINATION` were already defined, unused, in Sprint 1-3 —
  "architected now, intentionally not built," exactly as the roadmap described). One sample per
  user-supplied point, never synthetic intermediate samples along a route WakeWindow doesn't
  know the real shape of — matching the sprint brief's "meaningful sample points... not
  continuous 500-point sampling."

## What's built (Sprint 4 domain foundation + Sprint 5 real assessment/UI/persistence)

**Sprint 4 (domain only):** `MarineTripPlan`, `PlanningWaypoint`, `TripLeg`,
`TripLegEstimator.estimateLegs`/`routeSamples`/`totalPlanningDistanceNm`.

**Sprint 5 adds a real, working Mode B end to end** — see [TRIP_ASSESSMENT.md](TRIP_ASSESSMENT.md)
for the full assessment pipeline:

- Timed, per-point weather/tide/current/observation fetching (`DefaultTripBoatingRepository`) —
  every planning point, and every generated intermediate weather sample, is evaluated at its own
  expected arrival time and location, never a shared departure-hour snapshot.
- Worst-case trip-level gating (`TripAssessmentBuilder`) — one hazardous segment determines the
  overall trip category, never averaged away by calm segments on either side.
- Deterministic intermediate weather sampling on long legs (`WeatherSampleGenerator`), always
  role `RouteSampleRole.WEATHER_SAMPLE`, never presented as a planning waypoint.
- A real trip editor (`ui/tripplan/TripPlanScreen`) and hierarchical result screen
  (`ui/tripresult/TripResultScreen`), reachable from Home via a "Trip" entry point distinct from
  "Day outing," with waypoint search reusing the existing place-search screen.
- Local persistence (`SavedTrip`/`SavedTripRepository`/Room), with a "Saved trips" section on
  Home mirroring "Saved launches" (see the sprint brief's Phase 15) — a trip is remembered
  automatically after a successful assessment, exactly like a Mode A launch's "usually 7 AM"
  recall, never a full multi-provider re-assessment fired just because Home rendered.
- Documented, enforced trip complexity limits (`TripPlanLimits`) and honest forecast-horizon
  behavior — see TRIP_ASSESSMENT.md.
- Durable caching extended to the weather/alert/tide/current fan-out, shared between Mode A and
  Mode B — see [CACHE_POLICY.md](CACHE_POLICY.md).

## What's explicitly NOT built yet

- **Alternative departure-window scan** (sprint brief Phase 13, an explicit stretch goal) — "leaving
  90 minutes earlier avoids the highest storm risk" is not built; see TRIP_ASSESSMENT.md.
- **Per-point timezone resolution/display** — every point renders in the departure zone; see
  TRIP_ASSESSMENT.md's own honest limitation writeup.
- **Route-aware marine weather along a real charted course, tidal-current-on-route effects,
  multi-day outlook beyond a single trip's own timeline.** All remain out of scope per
  [ROADMAP.md](ROADMAP.md)'s "architected now, intentionally not built" list.

## Weather samples vs. planning waypoints

Sprint 5 introduces a second kind of point: a generated intermediate weather-evaluation sample
on a long leg (see [TRIP_ASSESSMENT.md](TRIP_ASSESSMENT.md) "Intermediate weather sampling"),
carrying `RouteSampleRole.WEATHER_SAMPLE` and `TripPointKind.WEATHER_SAMPLE`. This is never the
same thing as a `PlanningWaypoint`/`RouteSampleRole.WAYPOINT`: a weather sample was never chosen
by the user, has no name, and must never be presented as a recommended or navigable stop —
`ui/tripresult/TripResultScreen.kt` labels it plainly "Weather sample" in the timeline, distinct
from a named planning waypoint or "Departure"/"Destination."

## Non-navigation language, end to end

WakeWindow's Mode B UI and domain model consistently use: "planning distance" (not "route
distance" or "navigable distance"), "planning waypoint" (not "navigation waypoint"), "weather
sample" (not "waypoint" or "stop," for a generated point), "estimated arrival based on your
cruise speed" (not "ETA" presented as authoritative), and never render a line on a map between
waypoints implying a charted, safe course — WakeWindow has no map screen and no charting data to
justify one. `ui/tripplan/TripPlanScreen.kt` shows the sprint brief's own disclaimer text
directly in the editor: *"WakeWindow estimates conditions between user-selected planning points.
It does not calculate a navigable marine route."*
