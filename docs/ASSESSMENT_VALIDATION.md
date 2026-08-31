# WakeWindow — Assessment Validation

This document audits WakeWindow's scoring output against real, live provider data rather than
assuming the arithmetic is correct just because it's internally consistent. Both worked
examples below were captured from an actual run on a Pixel emulator against live NWS,
Open-Meteo, NOAA CO-OPS, and NOAA NDBC endpoints on 2026-08-30/31 - nothing here is
simulated. See [MARINE_SCORING.md](MARINE_SCORING.md) for the algorithm these results are
produced by.

## Worked example 1 — Port Canaveral, FL (full data coverage)

**Plan:** Port Canaveral (28.408, -80.591), departure 10:00 PM, return 6:00 AM (an overnight
outing), Aug 30-31 2026.

**Result: `97 — EXCELLENT`**

### What contributed

| Input | Contributed? | Detail |
|---|---|---|
| NWS `forecastGridData` (general) | Yes | Air temp, precipitation probability, thunderstorm probability |
| NWS `forecastGridData` (marine) | Yes | Wind, gust, wave height/period (point resolved as marine-adjacent) |
| Open-Meteo general | Yes | Second source for wind/temp/precip, folded into consensus |
| Open-Meteo Marine | Yes | Second source for wave height/period, folded into consensus |
| NOAA CO-OPS tide | Yes | Station **TRDF1** ("Trident Pier, Port Canaveral") - the same station CO-OPS returned as nearest during Sprint 1 API testing |
| NOAA NDBC buoy | Yes | Station **41009** ("CANAVERAL 20 NM East of Cape Canaveral, FL"), 23 NM from the launch, observed 41 min before the query (FRESH) |
| Marine alerts | Yes (none active) | NWS `/alerts/active` succeeded, zero alerts overlapped the outing window |

**Departure conditions:** wind 5 kt (gusts 8 kt), seas 0.3 ft (4s period), tide 3.8 ft and
`NEAR_HIGH`, thunderstorm probability 12%. All three legs (departure/underway-worst-hour/
return) independently scored `EXCELLENT` - a calm overnight window with nothing that gates.

**Best Window:** the engine reported *"Your planned window is excellent"* rather than a
different recommended window, because the entire 10 PM-6 AM span the user already chose
scored GOOD-or-better throughout (see [MARINE_SCORING.md](MARINE_SCORING.md) "Best Window" for
why this framing matters - a "Best Window" card implying a better option exists would be
actively misleading here).

**A real forecast/observation disagreement was detected and correctly downgraded
confidence:** the NDBC 41009 buoy was reporting **2.3 ft** seas at the time of the query,
while the merged forecast for that hour said **0.3 ft** - an 8x difference, well past the
1.5 ft materiality threshold. This is exactly the scenario the sprint's disagreement-detection
requirement anticipated, and it happened with real live data, not a constructed test case. The
UI surfaced it under both "Forecast vs. observed" and as a confidence limitation, and overall
confidence was correctly downgraded from what would otherwise have been HIGH to **MEDIUM**
specifically because of it - even though every one of the four evidence checklist items
("NWS/general weather forecast", "Marine (wind/wave) forecast", "NOAA tide station", "Nearby
buoy observation") was independently available. This is the intended behavior: full evidence
coverage does not guarantee HIGH confidence if the evidence disagrees with itself.

**Would removing Open-Meteo change the result?** NWS `forecastGridData` alone already
supplied wind, gust, wave height, and wave period for this point (it resolved as a
marine-classified grid - see [DATA_SOURCES.md](DATA_SOURCES.md)), so the qualitative result
(EXCELLENT, no gates) would not change. Consensus would fall back to single-provider
confidence handling (see [MARINE_SCORING.md](MARINE_SCORING.md) "Confidence") for any field
Open-Meteo was the only other contributor to, which in this case was none - NWS alone covered
every scored field. This confirms WakeWindow is not structurally dependent on Open-Meteo for
this location, satisfying the commercial-licensing constraint in
[DATA_SOURCES.md](DATA_SOURCES.md).

**Missing-field audit:** no scored field was null for this plan - the one gap (no current/tidal-current
station - `CurrentProvider` has no implementation this sprint) is listed explicitly as a
confidence limitation rather than silently absent.

## Worked example 2 — Clinton Lake, KS (degraded / inland data)

**Plan:** Clinton Lake Visitor's Center, Kansas (inland, non-tidal), departure 9:00 PM, return
5:00 AM.

**Result: `92 — CAUTION`, "Heat Advisory in effect"**

### What contributed

| Input | Contributed? | Detail |
|---|---|---|
| NWS `forecastGridData` (general) | Yes | Wind 8 kt, gusts 18 kt, thunderstorm probability 2% |
| NWS `forecastGridData` (marine fields) | **No usable data** | Point resolved as `type: land`; wave/swell fields present in the response schema but null for every hour, as expected inland |
| NOAA CO-OPS tide | **Not applicable** | `TideStationOutcome.NotTidal` - no station within 150 NM; correctly rendered as "Not tidal," not a blank field or an error |
| NOAA NDBC buoy | **Unavailable** | No station within the 75 NM search radius - correctly rendered as absent, not a fabricated distant reading |
| Marine alerts / general NWS alerts | Yes | A **Heat Advisory** was genuinely active for the area at query time |

**This is exactly the graceful-degradation case the product brief requires**: a location with
essentially no marine data infrastructure still produces a real, non-crashing, honestly-labeled
assessment. The confidence card showed the NWS/general and marine-forecast-call items checked
but explicitly listed "No marine wave/swell forecast available for this location," "No tide
station within range," and "No nearby buoy observation available" as limitations - it never
implied wave or tide data existed where none does.

**The alert-gating pipeline generalized correctly to a non-marine-specific alert.** WakeWindow's
`NwsMapper.classify()` has no special case for "Heat Advisory" - it fell through to the generic
`"advisory" in event` branch, which classifies as `ADVISORY` severity with
`vesselSizeExemptApplicable = false`. Per [MARINE_SCORING.md](MARINE_SCORING.md)'s gating
table, a non-exempt advisory caps every vessel size at `CAUTION` regardless of the otherwise-calm
numeric forecast (8 kt wind, 2% storm risk) - which is exactly what happened: the overall
category was `CAUTION`, not the `EXCELLENT` the raw wind/storm numbers alone would imply. This
is a deliberately conservative, broad design choice worth stating explicitly: **any** NWS
advisory-or-worse alert active during the outing gates the score, not only the marine-specific
event names (Small Craft Advisory, Gale Warning, etc.) called out in
[MARINE_SCORING.md](MARINE_SCORING.md)'s table. A heat advisory is a real safety-relevant
factor for a boating day (dehydration/heat exhaustion risk while underway), so this is treated
as correct behavior, not a bug - but it does mean an unrelated-sounding advisory (e.g. an Air
Quality Alert, were NWS to issue one as an "Advisory") would also gate. This tradeoff - broad,
conservative gating vs. a narrower marine-only allowlist - is intentional for this sprint; a
future sprint could narrow it if false positives from unrelated advisories become a problem in
practice.

**Missing-field audit:** `waveHeightFt`, `wavePeriodSec`, `tideHeightFt`, `tideTrend`, and
buoy-sourced fields were all null for every hour of this plan. None of them contributed a
scoring deduction (there is no "assume calm" default anywhere in `MarinePointScorer` - see
[MARINE_SCORING.md](MARINE_SCORING.md) "Missing data policy"), and confidence was
independently downgraded to `MEDIUM` to reflect the genuinely thinner evidence base, separate
from and in addition to the Heat Advisory's category cap.

## Missing-data policy (audited and enforced)

This is the specific class of bug the sprint asked to be audited for: **absence of evidence
must never read as "conditions must be fine."** The policy, as actually implemented:

| Missing evidence | Effect |
|---|---|
| A single scored field (e.g. `waveHeightFt`) is null for an hour | **No score effect** - the corresponding gate/deduction in `MarinePointScorer` is skipped entirely (`?.let { }`), not treated as zero/calm. Confidence is downgraded (see next row). |
| 1-2 of {wind, wave, thunderstorm, visibility} missing for an hour | Confidence floored at `MEDIUM` for that hour, with an explicit reason ("Missing wave height for this hour") |
| 3+ of those fields missing for an hour | Confidence floored at `LOW` |
| No marine data at all for the whole plan (`hasAnyMarineData == false`) | Confidence floored at `MEDIUM`, reason: "No marine (wave/tide) data available for this location" |
| No conditions at all for an hour (every provider failed) | That point's category is `UNAVAILABLE`, score `0` - explicitly not folded into an average that could look acceptable |
| Marine alert check **fails** (network/provider error) | **Fixed this sprint** - previously this silently fell through to "zero active alerts," identical to a successful check that found none. Now: confidence for every hour is downgraded to at most `MEDIUM` with the explicit reason "Marine alert status could not be verified - active warnings may not be reflected." A real Special Marine Warning during a provider outage can no longer be silently absent from the assessment's confidence story (it can still be absent from *gating*, since gating fundamentally requires the alert data to exist at all - but the UI will say so instead of implying "checked, all clear"). |
| Tide/current station beyond a useful distance | Treated as `NotTidal`/`NoStationNearby` (not "station found, ignore the huge distance") - CO-OPS stations beyond 150 NM and NDBC stations beyond 75 NM are excluded from selection entirely, per the distance policy in [DATA_SOURCES.md](DATA_SOURCES.md) |
| Buoy observation is stale (`STALE`/`UNUSABLE`) | Excluded from disagreement detection and never silently blended into the forecast timeline (see [MARINE_SCORING.md](MARINE_SCORING.md) "Forecast vs. observation"); still surfaced for provenance ("last report N min ago") so a user can judge for themselves |

Tests specifically proving this policy (see `DefaultBoatingRepositoryTest`,
`MarinePointScorerTest`): missing wave data does not produce a wave-related hazard or an
artificially high score; a failed alert check demonstrably reduces confidence rather than
reading as confirmed-clear; a location with zero marine coverage still produces a scored,
non-crashing result with appropriately reduced confidence.

## Known remaining gap

The evidence checklist's "Marine (wind/wave) forecast" item originally checked whether a
marine-forecast HTTP call *succeeded*, not whether it returned *usable* wave data - which
meant an inland point (where NWS's `forecastGridData` schema still includes wave-shaped fields,
just null) could show a checkmark next to "Marine forecast" while Seas displayed a bare dash.
Caught during this sprint's live Clinton Lake validation and fixed: the check now requires at
least one hour with a non-null `waveHeightFt` before marking that evidence item available.
