# WakeWindow — Marine Scoring

This document specifies the algorithm implemented in
`domain/scoring/` (see [ARCHITECTURE.md](ARCHITECTURE.md) for where these types live). It is
a marine-specific design, not a reskin of RideCast's commute scoring — RideCast never has to
reason about a return trip eight hours after departure, never treats a single hazard as an
absolute gate, and never varies its thresholds by vehicle. All three are first-class
requirements here.

## Output shape

```
BoatingWindowAssessment
├── departureAssessment: PointAssessment      // at launch, at planned departure time
├── underwayAssessments: List<PointAssessment> // one per hourly sample between departure and return
├── returnAssessment: PointAssessment          // at launch, at planned return time
├── overallAssessment: OverallAssessment       // category + score + top reasons, for the home screen
├── bestWindow: BestWindow?                    // best contiguous span, independent of the user's chosen times
├── worstHazards: List<Hazard>                 // ranked, most important first
└── confidence: Confidence                     // HIGH / MEDIUM / LOW / UNAVAILABLE, with reasons
```

`PointAssessment` is the atomic unit: a `BoatingCategory`, a 0–100 `score`, the
`MarineConditions` it was computed from, and the list of `Hazard`s that applied at that
place/time. Everything above is built by combining `PointAssessment`s — there is no
alternate scoring path for "the whole day" that isn't just a specific combination of
per-point assessments. This is what makes the system explainable: every number on screen can
be traced back to a specific hour's conditions.

## Categories

```
EXCELLENT   score ≥ 85, no gate active
GOOD        score ≥ 70, no gate worse than GOOD
CAUTION     score ≥ 50, or a CAUTION-level gate is active
POOR        score ≥ 25, or a POOR-level gate is active
NO_GO       score < 25, or a NO_GO-level gate is active
UNAVAILABLE insufficient data to score this point at all
```

A gate can only ever pull a category *down* from what the raw score implies, never up. A
95/100 raw score next to an active Gale Warning is still capped at POOR — the gate wins.
`UNAVAILABLE` is not a failure state to hide; a point with no usable wind/wave/marine data at
all is reported as such rather than silently scored as if conditions were calm.

## Point-level scoring

Starting from 100, deductions are applied per factor, each scaled by how far the observed
value sits past the *vessel's* tolerance for that factor (from `VesselProfile`), not a fixed
constant — this is what makes the same 20 kt wind forecast read as `GOOD` for a center
console and `CAUTION` for a loaded-down pontoon. A factor with no data available contributes
no deduction and no gate — it's simply absent, and absence is tracked separately for
`Confidence`, never treated as "conditions must be fine."

Factors evaluated, when present: sustained wind, gusts, wave height, wave period (a short,
steep period is treated as worse than the same height at a long period), thunderstorm
probability, precipitation probability, visibility, and swell height where distinct from wind
wave.

### Gates (hazards that cap the category outright)

These exist because averaging is the wrong operation for them — a few points off an
otherwise-sunny score is not how a Gale Warning should be represented.

| Gate | Trigger | Cap |
|---|---|---|
| Thunderstorm probability ≥ vessel's `thunderstormTolerance` | Forecast | ≥ tolerance → `CAUTION`; ≥ tolerance + 30 pts → `POOR`; ≥ 90% → `NO_GO` |
| Wave height ≥ 1.0× vessel's `waveTolerance` | Forecast/observation | `CAUTION` at 1.0×, `POOR` at 1.25×, `NO_GO` at 1.5× |
| Gust ≥ vessel's `gustTolerance` | Forecast/observation | `CAUTION` at tolerance, `POOR` at tolerance + 10 kt |
| Visibility below vessel's `visibilityTolerance` | Forecast/observation | `CAUTION` at tolerance, `POOR` below half of it |

A gate is a floor on the *category*, not a fixed penalty on the *score* — the brief is
explicit that a severe marine warning "should not merely subtract 15 points from an
otherwise pleasant day." Marine alerts are gated separately, by relevance rather than a
blanket policy — see "Alert relevance model" below, which replaces the Sprint 2 table that
used to live in this section.

## Alert relevance model

**Sprint 2's policy — any NWS advisory-or-worse alert caps the category at `CAUTION`,
regardless of what kind of threat it actually represents — was a real gap.** Treating a Heat
Advisory identically to a Small Craft Advisory conflates two different kinds of consequence: one
threatens the people aboard without being a navigation hazard, the other threatens safe
operation of the vessel itself. Sprint 3 replaces the blanket policy with
`MarineAlertImpact` (`domain/alert/MarineAlertImpact.kt`), classified per event by
`NwsMapper.classify()`:

| Event | Impact category | Behavior | Effect |
|---|---|---|---|
| Hurricane/Tropical Storm Warning, Special Marine Warning, Severe Thunderstorm Warning, Tornado Warning | `SEVERE_WEATHER` / `MARINE_NAVIGATION` | `HARD_GATE` | `NO_GO` |
| Gale Warning, Storm Warning | `MARINE_NAVIGATION` | `CATEGORY_CEILING` | `POOR` |
| Small Craft Advisory | `MARINE_NAVIGATION` | `CATEGORY_CEILING` | `CAUTION` — **always, for every vessel size** (see "The vessel-size exemption is gone" below) |
| Dense Fog Advisory | `MARINE_NAVIGATION` | `CATEGORY_CEILING` | `CAUTION` |
| Coastal Flood Advisory/Warning (not a Watch) | `COASTAL_ACCESS` | `CATEGORY_CEILING` | `CAUTION` — affects the launch/return infrastructure, not conditions on the water |
| Excessive Heat Warning | `HUMAN_EXPOSURE` | `CATEGORY_CEILING` | `CAUTION` |
| Heat Advisory | `HUMAN_EXPOSURE` | `SCORE_DEDUCTION` | 15 points — real, but survivable, and not a navigation hazard |
| Wind Chill / Freeze / Frost / Cold Weather Advisory | `HUMAN_EXPOSURE` | `SCORE_DEDUCTION` | 10 points |
| Air Quality Alert | `INFORMATIONAL` | `INFORMATIONAL_ONLY` | Surfaced, no scoring effect |
| Any unrecognized *Watch* | `SEVERE_WEATHER` | `SCORE_DEDUCTION` | 5 points |
| Any unrecognized *Warning* | `UNKNOWN` | `CATEGORY_CEILING` | `CAUTION` — still gated, since NWS reserves "Warning" for serious products, but not assumed marine-specific |
| Any unrecognized *Advisory* | `UNKNOWN` | `SCORE_DEDUCTION` | 8 points |
| Anything else | `UNKNOWN` | `INFORMATIONAL_ONLY` | Surfaced, never silently dropped |

Every active alert is **always surfaced** as a `Hazard` (with `"(informational)"` appended to
the message when it has no scoring effect) — nothing "disappears" just because it doesn't gate.
Re-validated live: see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md) "Worked example 2,"
where this exact change moved a real Clinton Lake, KS assessment from `CAUTION` (Sprint 2's
blanket cap) to `GOOD` (a real 15-point deduction, no ceiling) for the identical live Heat
Advisory - the intended, corrected behavior, not a loosening of safety.

**The vessel-size exemption is gone.** Sprint 2 let a vessel with `VesselProfile.isSmallCraft =
false` skip a Small Craft Advisory's category ceiling entirely, on the theory that the advisory
is written for small vessels specifically. This was a real design mistake: it meant an active
Small Craft Advisory could be completely invisible to the score for a larger boat. Sprint 3
removes this - `MarineAlert.vesselSizeExemptApplicable` is kept on the type only for source
compatibility, but `MarinePointScorer` no longer reads it. An active Small Craft Advisory now
applies its `CAUTION` ceiling **regardless of vessel size**, always.

**Classification is deliberately broad, not a marine-only allowlist**, for events that don't
match a specific rule above - both the `UNKNOWN`/`CATEGORY_CEILING` and `UNKNOWN`/
`SCORE_DEDUCTION` fallback rows exist so an unrecognized-but-real NWS alert is never silently
ignored, even though it's no longer assumed to be as severe as a classified marine hazard by
default.

**Gating uses whether the alert overlaps the outing window, not whether it's active "right
now."** `MarineAlert.overlaps(outingStart, outingEnd)` is what determines if a hazard is even
considered for gating — a Small Craft Advisory that starts two hours into a six-hour outing
still gates the hours it covers, and one that expired before departure or starts after the
planned return never reaches the scorer at all. `MarineAlert.timingRelativeTo()` is a separate,
UI-only concept (`ACTIVE_NOW` / `STARTS_DURING_OUTING` / `ALREADY_EXPIRED` /
`OUTSIDE_OUTING_WINDOW`) used purely for explanatory text ("this warning starts during your
outing" vs. "in effect right now") — it never affects scoring.

## Window-level aggregation — why return conditions dominate

The whole point of `BoatingWindowAssessment` is that the outing is one decision, not three
independent ones. Two rules make return conditions impossible to average away:

1. **Overall category is the worst category anywhere in the window** — `min(departure,
   worst hour of underway, return)` by category rank, not a weighted average of scores. Eight
   hours of `EXCELLENT` next to one `NO_GO` hour near the planned return produces an overall
   `NO_GO`/`POOR`, not a blended `GOOD`.
2. **The overall score (the 0–100 number, used for display and for ranking `bestWindow`
   candidates) is a weighted blend that intentionally over-weights the return leg**:
   departure 20%, underway average 30%, return 35%, plus a 15% term that is a direct penalty
   from the single worst hazard anywhere in the window, scaled by how close in time that
   hazard is to the *return* hour specifically (a worsening trend timed near return costs
   more than the same severity earlier in the day). This directly implements the product
   brief's Port Canaveral example: a fine morning with thunderstorms arriving near a 4 PM
   return is `CAUTION`, driven by the return-proximity term, not diluted by the good morning
   hours.

`overallAssessment.reasons` is generated from the specific `Hazard`s that drove the category
down — never a generic "conditions may be unfavorable" string. Each `Hazard` records what
triggered it, its value, its threshold, and the hour it applies to, so the UI can render
exactly the kind of explanation the brief asked for:

```
CAUTION — 68
Wind gusts reaching 24 kt after 2 PM.
Seas increasing from 2 ft to 4 ft.
Thunderstorm risk increases near your planned 4 PM return.
High tide at the launch is 6:42 AM, falling through the morning.
Marine data confidence: HIGH.
```

## Best Window

`bestWindow` is not hand-written copy — it is derived by scanning the scored hourly sequence
(currently bounded to the plan's own departure/return span; extending it to a wider
daylight/available-forecast range so WakeWindow can suggest "you asked about 8–4, but
7:30–1:30 is actually your best window" only requires passing more points into the same
`BestWindowFinder.find()` call, not changing the algorithm) for the longest contiguous span
where every hour scores `GOOD` or better, breaking ties by highest average score.

**"Best Window" only appears as a distinct recommendation when it's genuinely different from
what the user planned.** `BestWindow.matchesPlannedWindow` is true when the found span starts
and ends within 20 minutes of the plan's own departure/return (the tolerance absorbs rounding,
not a materially different window). When it's true, the UI says *"Your planned window is
excellent"* instead of presenting a "Best Window" card — a card implying a better alternative
exists when the user's own plan already is the best available window would be actively
misleading, not just unhelpful. Confirmed live: a fully-calm Port Canaveral overnight plan
correctly produced the "planned window is excellent" framing rather than a redundant
"Best Window: [the same hours you already picked]" (see
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)).

**Reasons are generated deterministically from the scored data, never hand-written or
LLM-generated prose.** `BestWindow.reasons` describes the stable favorable conditions inside
the window (e.g. "Wind stays at or below 12 kt," "Seas stay 1.0 to 2.0 ft" — comparing
*display-rounded* values, not raw doubles, so two hours that both round to "0.3 ft" never
render as the redundant-looking "0.3 ft to 0.3 ft") and, when the window ends before the
hourly sequence does, the specific hazard driving that boundary (reusing the same `Hazard`
produced by `MarinePointScorer`, e.g. "Wind gusts reaching 24 kt after that").

**`recommendReturnBy` is set only when it says something the plan doesn't already know.** If
the best window's end falls before the plan's own return time, that end instant is surfaced as
"Return by X recommended" — a genuinely different, earlier return than what the user planned.
If the best window extends through (or matches) the planned return, this is left null.

## Confidence

| Level | Condition |
|---|---|
| `HIGH` | Marine forecast available for the point, plus a tide station (for tidal water) and/or a nearby (< ~15 NM) marine observation station where applicable |
| `MEDIUM` | Marine forecast available, but the nearest relevant observation/tide station is distant or absent, or the forecast lead time is long (multi-day out) |
| `LOW` | Only a fallback/general forecast is available for a marine-relevant field, or all nearby stations are well beyond a useful distance |
| `UNAVAILABLE` | No usable weather data at all for the point/time |

Confidence is computed per point and then rolled up to the window level as the *minimum*
confidence across departure/underway/return, for the same reason category uses worst-of: a
plan is only as trustworthy as its weakest leg. The specific missing pieces are always listed
(e.g., "nearest tide station is 42 NM away" or "no marine forecast for this inland lake — general
weather only"), never just a bare confidence label. The full missing-data policy — exactly
which absences reduce confidence, which are scoring-neutral, and which are structurally
impossible to hide (like a failed alert check) — is audited in detail in
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md) "Missing data policy."

**Full evidence coverage does not guarantee HIGH confidence.** `BoatingWindowAssessment.evidence`
(a `ConfidenceEvidence` of checklist items — "NWS/general weather forecast," "Marine (wind/wave)
forecast," "NOAA tide station," "NOAA current station," "Nearby buoy observation" — plus a
`limitations` list) is generated separately from the confidence *level* precisely so the UI can
show "every source responded" and *still* explain a MEDIUM/LOW confidence level, e.g. when a
forecast/observation disagreement is detected (see "Forecast vs. observation" below) — confirmed
live at Port Canaveral during Sprint 2, where confidence was correctly MEDIUM despite full
evidence coverage because the nearest buoy's observed seas materially disagreed with the
forecast. Re-validated live in Sprint 3 with the corrected station-local comparison — see
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md).

## Tide trend

`MarineConditions.tideTrend` is one of five explicit `TideTrend` states — `RISING`, `FALLING`,
`NEAR_HIGH`, `NEAR_LOW`, or `UNKNOWN` — and is **never left as a bare `null`** whenever real
tide events exist for the water body. A `null` value means exactly one thing: no tidal data
applies here at all (an empty predicted-events list, e.g. a non-tidal inland lake), and the UI
renders that case as "Not tidal," not a blank dash.

This fixes a real Sprint 1 bug where an hour falling outside the fetched window's bracketing
events (near the edge of the day) fell through to a bare `null` trend that the UI rendered as
an unexplained dash next to a perfectly valid tide height. `TideTimeline.trendAt()` now always
resolves to an explicit state: within 45 minutes of a charted high or low, the trend is
`NEAR_HIGH`/`NEAR_LOW`; heading toward or away from a known extreme at the edge of the fetched
window still resolves to `RISING`/`FALLING` by inferring direction from whichever single
extreme is known; only a location with zero tide events at all produces `null`. Confirmed live:
the Port Canaveral overnight plan showed "3.8 ft / Near high" at departure — a case that, before
this fix, would very plausibly have rendered as "3.8 ft / —".

## Graceful degradation (inland water, sparse data)

A location with no tide station, no marine buoy, and no NWS marine grid coverage (e.g., an
inland lake) still produces a `BoatingWindowAssessment` — built from whatever
`GeneralWeatherProvider`/wind/thunderstorm data is available, with every marine-only field
left `null` in `MarineConditions`, and `Confidence` explicitly reflecting the missing
categories. It never fails outright, and it never invents a wave height or tide time that
doesn't exist for that water body.

## Forecast vs. observation

A forecast says what's expected *later*; a buoy observation says what's happening *now*. These
are never conflated. `DefaultBoatingRepository` never blends an NDBC reading directly into the
hourly forecast timeline as if both represented the same instant — instead:

- The nearest usable buoy/station is selected via `NdbcStationSelector`, ranked by freshness
  tier first, then sensor capability (wind+wave beats wind-only beats wave-only), then
  distance — never simply "closest," per the product brief. Selection is capped at 75 NM;
  nothing farther is considered "nearby" for this location. See
  [DATA_SOURCES.md](DATA_SOURCES.md) for the freshness-tier thresholds and their rationale.
- **The observation is compared against a forecast for the station's own coordinates, never
  the launch's** (Sprint 3 fix — see [STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md)
  for the full mechanism and why comparing against the launch's forecast was a real bug: a
  buoy 23 NM offshore disagreeing with an *inshore* forecast is a location difference, not a
  forecast error). The comparison uses whichever forecast hour is nearest the observation's own
  timestamp, within a 90-minute alignment window.
- A `STALE` or `UNUSABLE` observation (see [DATA_SOURCES.md](DATA_SOURCES.md)) is excluded from
  disagreement detection entirely, though the station and its age are still surfaced for
  provenance ("last report 3h 20m ago").
- `MarineDisagreementDetector` flags a material difference per field (wind ≥ 8 kt, gust ≥ 10 kt,
  wave height ≥ 1.5 ft, visibility ≥ 1 NM, air temperature ≥ 10°F).

## Observation influence on assessment

Sprint 2 only ever used a forecast/observation disagreement to *explain* a confidence
downgrade — it never actually changed the category. Sprint 3 lets a station observation
influence the departure-hour category, but only through an explicit, traceable mechanism, never
by averaging the observed value into the forecast:

`ObservationalCautionEvaluator.evaluate(comparison, departureTime, vessel)` returns a `Hazard`
(applied to the departure point exactly like a marine-alert gate) only when **all** of:

1. The station's `StationRepresentativeness` is `HIGH` or `MEDIUM` — `LOW`/`UNKNOWN` never
   influence the assessment, no matter how alarming the reading is.
2. The observation is within 3 hours of the planned departure — representative evidence about a
   moment far from departure still isn't evidence about departure itself.
3. At least one field is genuinely **worse** than what was forecast (higher wave/gust/wind, or
   lower visibility) — an observation that's merely *different* (warmer air temperature) or
   *better* than forecast never gates anything.

When it fires, the resulting `Hazard` caps the category at `POOR` if the observed value itself
exceeds the vessel's own tolerance, `CAUTION` otherwise — applied via the same `worstCategory()`
mechanism as every other gate, never a fixed point deduction. See
[STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md) for the full representativeness
model this depends on.

## Environment-aware evidence requirements

The same missing field means different things in different places. Missing wave data at a
genuinely coastal/offshore launch is a real evidence gap — there's a real, unaccounted-for sea
state. Missing wave data at an inland lake is not a gap at all — there's no sea state to
report. Sprint 2 treated both identically (missing data never gated, but also never
distinguished the two cases). Sprint 3's `EvidenceRequirementEvaluator.evaluate(environment,
conditions)`:

- For `NEARSHORE`/`OFFSHORE`/`HARBOR`/`ESTUARY`/`INTRACOASTAL`/`GREAT_LAKES` (the
  wave-relevant environments — see [STATION_REPRESENTATIVENESS.md](STATION_REPRESENTATIVENESS.md)),
  a missing `waveHeightFt` ceilings that point's category at `GOOD` — `EXCELLENT` is
  unreachable without the evidence a coastal location is expected to have, with an
  `EVIDENCE_INCOMPLETE` hazard explaining why.
- For `INLAND`/`RIVER`/`UNKNOWN`, no ceiling applies — missing wave data is expected, not a gap.
- The ceiling can only ever pull the category *down* from what the raw score/other gates
  already implied, exactly like every other gate — confirmed with a direct unit test showing a
  coastal point with otherwise-perfect wind/visibility/thunderstorm data still caps at `GOOD`,
  while the identical conditions at an inland point reach `EXCELLENT`.

## Vessel profiles

The MVP shipped one sensible default recreational profile with no selection UI. Sprint 3 adds
the first five user-selectable presets (`VesselProfile.presets()`), each a distinct,
internally-consistent tolerance profile: **Small recreational boat**, **Center console**,
**Pontoon**, **PWC (jet ski)**, and **Sailboat**. The numbers are deliberately coarse,
defensible starting points, not manufacturer sea-state specs — a PWC's low freeboard and lack
of a cabin make it meaningfully more wind/wave-sensitive than a similarly-sized console boat,
which the preset numbers reflect (lower gust/wave tolerance despite comparable length); a
sailboat's tolerance reflects motoring/day-sailing in developing weather, not a bluewater
passage, and it is not flagged `isSmallCraft` the way the others are.

Selection is a compact, single-row chip picker on the plan screen (never a full-screen editor
this sprint) and persists across app restarts via `VesselPreferenceStore` (a plain
`SharedPreferences` value — a single scalar for a single-user app doesn't warrant a new Room
entity/migration). **Vessel choice only ever changes which tolerances scoring reads — it can
never override, hide, or downgrade an active marine-alert gate.** The alert relevance model
above gates identically regardless of which preset is selected; only the numeric wind/wave/
gust/visibility thresholds vary by vessel.

## Explicitly not vessel-hardcoded

Every threshold in the gates table and every deduction curve reads from `VesselProfile`
(`windTolerance`, `gustTolerance`, `waveTolerance`, `thunderstormTolerance`,
`visibilityTolerance`). The scoring engine itself has no single-hardcoded-vehicle assumption
anywhere in it — a pontoon, a PWC, and a 34' cruiser run through the exact same functions with
different tolerance inputs.
