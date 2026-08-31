# WakeWindow — Assessment Validation

**Sprint 4 status:** this session's environment had no outbound network access to any provider
host (`api.weather.gov`, `gis.myfwc.com`, `tidesandcurrents.noaa.gov`, `ndbc.noaa.gov`,
`services7.arcgis.com`, `photon.komoot.io`, `api.open-meteo.com` were all confirmed
unreachable), so **no live validation was performed this sprint** and none of the entries below
were re-captured. Everything Sprint 4 changed (FWC facility fields, search ranking, vessel
profiles, caching, provider resilience, sunrise/sunset, trip planning) is implemented and
reasoned through at the source level only. Re-running the exact live validation this document
already describes for Port Canaveral and Clinton Lake, KS - plus checking the three newly-wired
FWC fields (`TotalLanes`, `Amenities`, `ContactPhone`) actually populate from real live
responses - is the mandatory first step once network access is available; see
[ROADMAP.md](ROADMAP.md) "Next sprint." The Sprint 3 findings below are preserved unmodified as
the last real, live-verified state.

This document audits WakeWindow's scoring output against real, live provider data rather than
assuming the arithmetic is correct just because it's internally consistent. Per Sprint 3's own
mandate, this revision does **not** preserve Sprint 2's conclusions by default - both worked
examples below were re-captured from a fresh run against live NWS, Open-Meteo, NOAA CO-OPS,
NOAA NDBC, Florida FWC, and USACE endpoints on 2026-08-31, using the exact same two saved
launches Sprint 2 validated (Port Canaveral and Clinton Lake Visitor's Center, Kansas), so the
comparison is apples-to-apples. See [MARINE_SCORING.md](MARINE_SCORING.md) for the algorithm and
[STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md) for the environment/
representativeness model this revalidation exercises.

## Worked example 1 — Port Canaveral, FL (`28.416056, -80.6078268`)

**Plan:** departure +1h, return +9h from query time, default vessel.

**Result: `96 — EXCELLENT`** (Sprint 2 reported 97/EXCELLENT for a different overnight window on
different live conditions - see "What actually changed" below for why a same-ballpark-but-not-
identical number is the expected, honest outcome here, not a regression.)

### What contributed

| Input | Contributed? | Detail |
|---|---|---|
| NWS `forecastGridData` (general + marine) | Yes | Point resolves marine-adjacent; wind, gust, wave height/period all present |
| Open-Meteo general + marine | Yes | Second concurrent source, folded into consensus |
| NOAA CO-OPS tide | Yes | Station within range; tide height/trend populated |
| NOAA CO-OPS current | **No** | Nearest current-prediction station (Fort Pierce Inlet, `FPI0901`) is **~58 NM away** - beyond the 50 NM cutoff in `CoopsCurrentProvider` (currents are hyper-local; a 58 NM-away reading isn't useful evidence for this inlet). Correctly reported as "No current station within range," not silently omitted the way it was pre-Sprint-3 (see "Known remaining gap" in the Sprint 2 revision of this doc - **closed** this sprint by actually implementing `CurrentProvider`, even though the honest answer for this specific launch is still "none available"). |
| NOAA NDBC buoy | Yes | Station **41009**, 22.99 NM away, FRESH (29 min old) |
| Marine alerts | Yes (none active) | Zero alerts overlapped the outing window |

**Water environment: `HARBOR`.** `WaterEnvironmentClassifier` resolved this from NWS's own
`land`/`marine` point type plus the nearest tide station's distance (≤5 NM) - see
[STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md). This is a genuinely new,
previously-absent piece of evidence about *where* this launch actually is, not just what the
forecast says.

### Priority 1/2 in action: the forecast-vs-observation comparison is now station-local

This is the specific bug Sprint 3 set out to fix. Sprint 2 compared the buoy's *observed* value
against the *launch's* forecast for the departure hour - a real methodology error, because
41009 sits 23 NM offshore and a launch-location forecast is not the correct thing to diff it
against. Sprint 3's `DefaultBoatingRepository.buildObservationComparison` instead fetches a
forecast **for 41009's own coordinates**, in a window around the observation's own timestamp,
and diffs against that.

Re-run today: `observationComparison.status = COMPARABLE`, **`disagreements = []`** - no
material difference between what was forecast for 41009's location and what 41009 actually
reported. Sprint 2's live run had found an 8x wave-height disagreement (0.3 ft forecast vs.
2.3 ft observed) at the launch-vs-buoy comparison; today's station-local comparison finds none.
**Both are true, honest results from live weather, not a contradiction** - Sprint 2's number
was a real, momentary reading from a different day/hour, and comparing it against the launch
forecast (rather than a station-local one) was always going to overstate how much it said about
the launch itself. The important fact this revalidation confirms is the *mechanism*: the
comparison is now measured at the right place, so whatever it reports - agreement or
disagreement - is actually evidence about that station's forecast skill, not an artifact of
comparing two different locations.

**Representativeness is honestly `MEDIUM`, not `HIGH`.** Even with zero disagreement,
`StationRepresentativenessEvaluator` does not report this as strong evidence for the launch:
41009 is 23 NM away, past the 10 NM `HIGH` threshold, so the result is `MEDIUM` with the reason
*"Station is 23 NM away and in a compatible environment, but not close enough for full
confidence."* This is new, and it is the honest answer - a 23 NM-offshore buoy agreeing with
its own local forecast says relatively little about conditions inside the harbor itself, even
though the environments are compatible (both coastal). Because representativeness is `MEDIUM`
(not `LOW` or `UNKNOWN`), the comparison is still eligible to influence the departure-hour
assessment via `ObservationalCautionEvaluator` **if** it had found a materially worse
observation near departure time - it did not, so no caution hazard was applied, which is the
same "no effect" outcome Sprint 2 reached, but for a traceable, examined reason instead of by
omission.

**Best Window:** unchanged in kind from Sprint 2 - `matchesPlannedWindow = true`, i.e. the
engine reports the planned window is already excellent rather than implying a better option
exists.

## Worked example 2 — Clinton Lake Visitor's Center, KS (`38.9435816, -95.3404357`)

**Plan:** departure +1h, return +9h from query time, default vessel.

**Result: `82 — GOOD`** (Sprint 2 reported `92 — CAUTION`. This is a real, deliberate,
explained change - see below. It is not the same live moment, but the category shift is not
explained by weather; it is explained by a specific Sprint 3 fix.)

### What actually changed, and why the category moved from CAUTION to GOOD

Sprint 2 gated **every** NWS advisory-tier alert at `CAUTION`, with no distinction between an
alert that threatens the vessel/water (Small Craft Advisory) and one that threatens the people
aboard without being a navigation hazard (Heat Advisory). A live Heat Advisory was active for
this Kansas location on both Sprint 2's and this sprint's test runs. Under Sprint 2's blanket
policy, that alone force-capped the score at `CAUTION` regardless of otherwise-calm numbers.

Sprint 3's `MarineAlertImpact` relevance model (see [MARINE_SCORING.md](MARINE_SCORING.md)
"Alert relevance model") classifies "Heat Advisory" as `AlertImpactCategory.HUMAN_EXPOSURE` /
`AlertImpactBehavior.SCORE_DEDUCTION` (a real 15-point deduction, not a category ceiling) -
because heat is a genuine but survivable risk, categorically different from a marine-navigation
hazard, and treating them identically was exactly the gap Sprint 3 was asked to close. Today's
result, `82 — GOOD` with a single "Heat Advisory in effect" entry in the hazard list and a real
point deduction visible in the score, is the corrected, intended behavior - not a loosening of
safety, but a more accurate one: the advisory is still surfaced, still visibly affects the
score, but no longer implies "this is as risky as an active navigation hazard" when it isn't.

### A real bug found and fixed during this exact revalidation

The first live run against Clinton Lake returned **nine identical** `"Heat Advisory in effect"`
entries in `worstHazards` - one per hourly sample of a nine-hour outing, because
`MarineScoreEngine.rankHazards`'s dedup key included the hour, and a single ongoing alert
produces one (identical) `Hazard` per hour it's active over. Fixed in the same commit: alert-
type hazards (`MARINE_ALERT_ADVISORY`/`SEVERE`/`EXTREME`) now dedup by `(type, message)` instead
of `(type, hour)`, since their message never varies hour to hour - a wave-height hazard, whose
message genuinely differs by hour, is unaffected. Re-run after the fix: exactly one Heat
Advisory entry. See `MarineScoreEngineTest` for the regression test, written directly from this
finding. This is precisely what Priority 8's live re-validation is for - not confirming existing
behavior looks right, but catching what only shows up against real data.

### What contributed

| Input | Contributed? | Detail |
|---|---|---|
| NWS `forecastGridData` (general) | Yes | Wind, thunderstorm probability |
| NWS `forecastGridData` (marine fields) | **No usable data** | Wave/swell fields present in the schema, null for every hour - expected inland |
| NOAA CO-OPS tide | **Not applicable** | `TideStationOutcome.NotTidal` - correctly rendered as "Not tidal" |
| NOAA CO-OPS current | **Not applicable** | No current station in range either - same honest "checked, none available" as Port Canaveral |
| NOAA NDBC buoy | **Unavailable** | No station within range |
| Marine alerts | Yes | Heat Advisory - see above |

**Water environment: `INLAND`** - correctly classified (NWS point type `land`, no tide station
within range at all), which matters for Priority 3: `EvidenceRequirementEvaluator` does **not**
ceiling this location's category for missing wave data, because `INLAND` is not in the
wave-relevant environment set - there is no wave data to be missing. (Contrast with Port
Canaveral: if *it* were missing wave data, `HARBOR` is wave-relevant and the category would be
capped at `GOOD`. This asymmetry, applied correctly to both real launches in this revalidation,
is the entire point of Priority 3.)

**Confidence: `MEDIUM`**, reasons "No marine (wave/tide) data available for this location" and
"Missing wave height for this hour" - present, accurate, and does not additionally cap the
category (that's the confidence system's job, not the evidence-ceiling's, and the two agree
without duplicating each other's effect).

### Priority 6 in action: real boating-relevant place discovery for both launches

A composite search for `"Port Canaveral"` returned the real **FWC Florida Boat Ramp Inventory**
result *"Port Canaveral Recreational Boat Launching Facility"* ranked first (source
`FWC_BOAT_RAMP`), ahead of seven Photon/OpenStreetMap geocoding matches for the same name
(including an unrelated result in British Columbia). A search for `"Clinton Lake"` returned a
real **USACE recreation-areas** result, *"Clinton Lake Kansas"* (source
`USACE_RECREATION_AREA`), ranked ahead of four Photon matches for other, different "Clinton
Lake"s (Illinois, Oklahoma, Pennsylvania). Both confirm the ranking and source-provenance work
described in [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md) against real data, not just the unit
tests' synthetic fixtures.

## Missing-data policy (re-audited this sprint, still holds)

**Absence of evidence must never read as "conditions must be fine."** Re-confirmed against both
live launches above, plus the environment-aware layer added this sprint:

| Missing evidence | Effect |
|---|---|
| A single scored field is null for an hour | No score effect - the field's gate/deduction is skipped, never treated as zero/calm |
| Missing wave data at a **wave-relevant environment** (`HARBOR`/`NEARSHORE`/`OFFSHORE`/`ESTUARY`/`INTRACOASTAL`/`GREAT_LAKES`) | **New this sprint** - the category is ceilinged at `GOOD`, with an `EVIDENCE_INCOMPLETE` hazard explaining why. Confirmed not to fire for Port Canaveral (wave data *was* present) - see [MARINE_SCORING.md](MARINE_SCORING.md) for the direct unit-level proof |
| Missing wave data at `INLAND`/`RIVER`/`UNKNOWN` | No ceiling - confirmed for Clinton Lake above |
| No current station within range | Reported as an explicit evidence-checklist item and limitation, not silently omitted - confirmed for both launches this sprint |
| A station observation exists but is a poor stand-in for the launch (far away, incompatible environment, stale) | **New this sprint** - `StationRepresentativenessEvaluator` reports `LOW`/`UNKNOWN` and `ObservationalCautionEvaluator` refuses to let it influence the assessment at all, regardless of how bad the observed reading is - see [STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md) |
| Marine alert check fails (network/provider error) | Confidence downgraded to at most `MEDIUM` with an explicit reason - unchanged from Sprint 2, re-confirmed by `DefaultBoatingRepositoryTest` |
| Tide/current station beyond a useful distance | Treated as "not applicable," not "found, ignore the distance" - 150 NM (tide) / 50 NM (current) cutoffs, both exercised live above |

## Known remaining limitations (honestly stated, not carried forward silently)

- **No real-time current *observations***, only *predictions* - the small subset of CO-OPS
  stations with physical current sensors (PORTS) is not yet distinguished from the ~4,400
  prediction-only harmonic stations `CoopsCurrentProvider` queries. See
  [DATA_SOURCES.md](DATA_SOURCES.md).
- **USACE recreation-area search is not a boat-ramp inventory** - it identifies that a Corps
  reservoir recreation area exists near a query, not whether it specifically has a launch ramp.
  The UI is honest about this (`"... · USACE recreation area"`, never `"Boat ramp"`) - see
  [PLACE_DISCOVERY.md](PLACE_DISCOVERY.md).
- **FWC boat ramp coverage is Florida-only** - a real, structural limitation of the source
  data, not a bug; other states have no equivalent authoritative source wired up yet.
- **Vessel presets' tolerance numbers are defensible starting points, not manufacturer specs**
  (see [MARINE_SCORING.md](MARINE_SCORING.md) "Vessel profiles") - real-world validation against
  owner's-manual sea-state limits is future work.
