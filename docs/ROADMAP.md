# WakeWindow — Roadmap

This roadmap separates what this sprint actually builds from what the architecture merely
*makes room for*. Building every seam listed under "Future" now would be architectural
theatre; the goal of this sprint is a real, working vertical slice.

## This sprint — MVP vertical slice

The target is: install WakeWindow on a device, pick a real launch point, pick departure and
return times, and get a genuine boating-day assessment built from live NWS/marine/tide data,
with reasons, with a saved launch that persists.

Concretely:

1. Clean WakeWindow project builds (Gradle, Kotlin, Compose, no RideCast baggage).
2. Nautical theme exists (light + dark, Inknaut splash, no white flash).
3. Launch/location can be selected (search + saved launches).
4. Departure time can be selected.
5. Return time can be selected.
6. NWS general forecast integration works (`api.weather.gov`).
7. One additional marine provider integration works (Open-Meteo Marine during development,
   behind a provider seam; NDBC/CO-OPS added where they land first).
8. Tide data works where NOAA CO-OPS station coverage exists.
9. Marine conditions map into clean, nullable-aware domain models — not stuffed into
   general-weather DTOs.
10. A boating assessment is calculated end-to-end (departure/underway/return, gated hazards,
    explainable score).
11. Reasons are displayed in the UI ("why is this CAUTION?").
12. Missing marine data degrades gracefully (inland test case: no tide, no buoy, no swell —
    still produces a general-weather-only assessment, clearly labeled with reduced
    confidence).
13. A saved launch persists locally (Room) across app restarts.
14. Runs on a real device/emulator (Pixel emulator image already present on this machine).

Test location for deterministic development: Florida's Space Coast (Port Canaveral area),
chosen for strong overlapping NWS + NDBC + CO-OPS coverage. The app itself is not
Florida-specific in any hardcoded way.

## Next sprint (highest-value follow-on)

Ranked by product value once the MVP vertical slice is proven:

1. **Best Boating Window** surfaced on the home screen, generated from the scoring engine's
   hourly assessments rather than hand-written copy.
2. **Launch Information screen** — facility intelligence (hours, fees, VHF channel, fuel,
   restrictions) as its own provenance-tracked view, reachable from a saved launch.
3. **NDBC buoy integration** as real observational validation/context (distance-aware,
   staleness-aware), not fabricated.
4. **Marine alerts** (Small Craft Advisory, Special Marine Warning, Gale Warning) wired as
   real hazard gates in the scoring engine, sourced from `api.weather.gov/alerts`.
5. **Vessel profile UI** — let users switch between a couple of built-in profiles (center
   console, pontoon, PWC) before investing in full custom profile editing.
6. Expand automated test coverage around real-world edge cases as they're found on-device
   (DST transitions, provider outages, sparse-data inland lakes).

## Architected now, intentionally not built this sprint

These are structurally supported (interfaces, domain seams, nullable models) so they are
additive later rather than requiring rework, but are explicitly out of scope for this sprint:

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

## Explicit non-goals

- WakeWindow is not, and will not become, a chartplotter or navigational instrument.
- WakeWindow will not fabricate a marine route by reusing road-routing output — a straight
  line between two ports routinely crosses land.
- WakeWindow will not invent marine data values when a provider has none. Missing beats
  wrong.
- No backend and no user accounts unless a real requirement emerges; all persistence is
  local (Room) for the foreseeable future.
