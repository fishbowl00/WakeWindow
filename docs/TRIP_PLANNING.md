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

## What's built this sprint

The domain model above: `MarineTripPlan`, `PlanningWaypoint`, `TripLeg`,
`TripLegEstimator.estimateLegs`/`routeSamples`/`totalPlanningDistanceNm`. Fully unit-tested
(`MarineTripPlanTest`) — deterministic, network-free.

## What's explicitly NOT built this sprint

- **No UI.** Per the sprint brief ("only build a polished trip screen if the underlying
  domain/use case is solid... otherwise create enough UI to validate the flow"), and given this
  session had no way to compile or run the app to validate a new screen, no Mode B screen ships
  this sprint. The domain layer is ready for one.
- **No per-waypoint weather fetch.** `TripLegEstimator.routeSamples()` produces the right
  *shape* of sample list, but `DefaultBoatingRepository.buildAssessment()` still only ever
  fetches weather for one location (Mode A's launch) — see its own class doc:
  *"Mode A always samples the same launch location... A real multi-location trip (Mode B) would
  extend this to fetch per distinct sample location."* Wiring a trip plan's samples through to
  a real per-location fetch (and reusing/adapting `MarineScoreEngine.assess()`, which already
  accepts an arbitrary `List<RouteSample>`) is real, scoped follow-up work, not attempted here.
- **No tidal-current-on-route effects, no multi-day outlook.** Both remain out of scope per
  [ROADMAP.md](ROADMAP.md)'s existing "architected now, intentionally not built" list.

## Non-navigation language, end to end

Anywhere this reaches a UI in a future sprint, it must keep using: "planning distance" (not
"route distance" or "navigable distance"), "planning waypoint" (not "navigation waypoint"),
"estimated arrival based on your cruise speed" (not "ETA" presented as authoritative), and it
must never render a line on a map between waypoints implying a charted, safe course — WakeWindow
has no map screen and no charting data to justify one.
