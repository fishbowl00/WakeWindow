# WakeWindow — Planning UX (Mode A)

Covers the same-launch planning flow (`PlanBoatScreen`) and the assessment screen's tide/
current presentation — the day-to-day "plan an outing" loop, as distinct from
[TRIP_PLANNING.md](TRIP_PLANNING.md) (Mode B, port-to-port).

## Quick plans

`domain/route/QuickPlanPreset.kt` defines `QuickPlanKind` (`MORNING`/`AFTERNOON`/`EVENING`/
`FULL_DAY`/`CUSTOM`) and `QuickPlanPresets.windowFor(kind, date, zoneId, sunTimes)`, which
returns a ready-to-apply departure/return `Instant` pair:

- **Morning**: real sunrise (see "Daylight context" below) through midday, falling back to
  7:00 AM through noon when no sun-time data is available (no launch picked yet, or the sun
  calculation is unresolved — see below).
- **Afternoon**: midday through 5:00 PM.
- **Evening**: 4:00 PM through 30 minutes after real sunset, falling back to 8:00 PM.
- **Full day**: the morning start through the afternoon end.
- **Custom** has no computed window at all — it means "keep using the manual date/time
  pickers," and `QuickPlanPresets.windowFor` throws `IllegalArgumentException` if called with
  it (a caller bug, not a runtime state).

Selecting a quick plan fills in the same manual pickers immediately visible below it — there is
no separate "quick plan mode" to exit, and the result stays fully editable afterward.
`QuickPlanPresetTest` guarantees every computed window has a strictly positive duration, even
in the unresolved-sun-time edge case.

## Plan summary

`PlanBoatScreen`'s `PlanSummaryCard` shows the currently-active plan (launch name, date,
departure → return, vessel) in one glance, once a launch and both times are set — so the user
never has to mentally reconstruct "what have I actually configured" by re-reading four separate
fields.

## Daylight context

`domain/sun/SolarCalculator.kt` computes real sunrise/sunset/civil-twilight locally — the
classic "Sunrise/Sunset Algorithm" (Almanac for Computers, 1990), a well-established
closed-form approximation, not a network call or a paid API for one supplementary field. Pure
Kotlin, `domain/`-safe (no `android.*`/`androidx.*`), fully deterministic. Every returned value
is a timezone-agnostic `Instant`; the caller converts to local display time with its own
`ZoneId`, matching [ARCHITECTURE.md](ARCHITECTURE.md) "Time zone handling."

`SunTimes.isPolarDayOrNight` is true, and `sunrise`/`sunset` are both `null`, when the sun
genuinely never rises or sets that day at that latitude — reported honestly, never guessed at.
The same applies within roughly half a degree of either pole, where the algorithm's own
division by `cos(latitude)` stops being meaningful.

`WakeWindowUiState.sunTimes` resolves this for the active launch's location and the departure
date; `returnAfterSunsetMinutes` is a simple derived fact ("your planned return is N minutes
after sunset") shown as **informational context only** — a post-sunset return is never treated
as a hazard or gate on its own, matching the sprint brief's explicit instruction not to treat
nighttime boating as automatically unsafe.

**Verification caveat, stated honestly:** `SolarCalculatorTest` asserts *qualitative,
deterministic* properties (sunrise before sunset, longer days in summer at mid latitudes, ~12h
days near the equator, polar night at high latitude in winter, determinism) rather than
exact-minute values checked against a live almanac — this session's environment had no outbound
network access to verify against a real reference source. The algorithm itself is a standard,
widely-reproduced one; independent verification against a live source (e.g. NOAA's own solar
calculator) is a reasonable next step once network access is available.

## Tide/current timeline

`AssessmentScreen`'s `TideCurrentCard` shows a whole-outing view rather than a single point
reading: tide height/trend at departure, the next high or low (whichever comes first) with its
predicted time, and the tide state at return; separately, current speed/direction at departure,
the next predicted turn (max flood/max ebb/slack), and current at return. Both sections are
explicitly labeled **"(prediction)"** — see [DATA_SOURCES.md](DATA_SOURCES.md) "Current
predictions": CO-OPS publishes harmonic predictions for the overwhelming majority of stations,
not a continuous sensor feed, and a prediction must never be presented as if it were a live
observation. A tide height is a prediction about the water surface — it is never used to imply
depth or under-keel clearance.

The card only renders when the departure hour actually has tide or current data (i.e. a
station was found in range) — an inland lake with no tidal data simply shows neither section,
consistent with the existing "missing beats wrong" policy.
