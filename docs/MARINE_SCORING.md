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

| Gate | Trigger | Cap | Vessel-size exempt? |
|---|---|---|---|
| Hurricane/Tropical Storm Warning, Special Marine Warning, or Severe Thunderstorm Warning in effect | NWS alert feed, classified `EXTREME` | `NO_GO` | No |
| Gale Warning / Storm Warning in effect | NWS alert feed, classified `SEVERE` | `POOR` | No — a gale is dangerous to any recreational vessel |
| Small Craft Advisory in effect | NWS alert feed, classified `ADVISORY` | `CAUTION` | **Yes** — a vessel with `VesselProfile.isSmallCraft = false` takes a 10-point deduction instead of a gate; the advisory is written for small vessels specifically |
| Dense Fog Advisory, or any other NWS advisory-tier alert (including non-marine ones — see below) | NWS alert feed, classified `ADVISORY` | `CAUTION` | No — fog, and most other advisory-tier hazards, are not a small-vessel-only problem |
| Any NWS Watch (Severe Thunderstorm Watch, Tornado Watch, etc.) | NWS alert feed, classified `WATCH` | 5-point deduction only, not a gate | n/a |
| Thunderstorm probability ≥ vessel's `thunderstormTolerance` | Forecast | ≥ tolerance → `CAUTION`; ≥ tolerance + 30 pts → `POOR`; ≥ 90% → `NO_GO` | n/a |
| Wave height ≥ 1.0× vessel's `waveTolerance` | Forecast/observation | `CAUTION` at 1.0×, `POOR` at 1.25×, `NO_GO` at 1.5× | n/a |
| Gust ≥ vessel's `gustTolerance` | Forecast/observation | `CAUTION` at tolerance, `POOR` at tolerance + 10 kt | n/a |
| Visibility below vessel's `visibilityTolerance` | Forecast/observation | `CAUTION` at tolerance, `POOR` below half of it | n/a |

A gate is a floor on the *category*, not a fixed penalty on the *score* — the brief is
explicit that a severe marine warning "should not merely subtract 15 points from an
otherwise pleasant day."

**Classification is deliberately broad, not a marine-only allowlist.** `NwsMapper.classify()`
recognizes the specific marine event names above by substring match, but *any* NWS alert whose
event text contains "advisory" or "warning" and isn't otherwise recognized still falls through
to the generic `ADVISORY`/`SEVERE` tier rather than being ignored — confirmed live during
Sprint 2 validation, where a genuine **Heat Advisory** active over an inland Kansas test
location correctly capped that assessment at `CAUTION` even though heat isn't a "marine" hazard
by name (see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)). This is intentional:
extreme heat is a real safety factor on the water (dehydration, heat exhaustion), and the
product's posture throughout is to gate conservatively on real NWS-issued hazards rather than
maintain a narrow marine-specific allowlist that could silently miss something relevant. A
future sprint could narrow this if unrelated advisories prove to be false positives in
practice.

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
forecast," "NOAA tide station," "Nearby buoy observation" — plus a `limitations` list) is
generated separately from the confidence *level* precisely so the UI can show "every source
responded" and *still* explain a MEDIUM/LOW confidence level, e.g. when a forecast/observation
disagreement is detected (see "Forecast vs. observation" below) — confirmed live at Port
Canaveral, where all four evidence items were available yet confidence was correctly MEDIUM
because the nearest buoy's observed seas materially disagreed with the forecast.

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
- The observation is compared against the **departure-hour forecast only**, and only when the
  observation is within a 3-hour "near-term" window of the departure instant — comparing a
  buoy reading from right now to a departure tomorrow afternoon would say nothing meaningful.
- A `STALE` or `UNUSABLE` observation (see [DATA_SOURCES.md](DATA_SOURCES.md)) is excluded from
  disagreement detection entirely, though the station and its age are still surfaced for
  provenance ("last report 3h 20m ago").
- `MarineDisagreementDetector` flags a material difference per field (wind ≥ 8 kt, gust ≥ 10 kt,
  wave height ≥ 1.5 ft, visibility ≥ 1 NM, air temperature ≥ 10°F) and, when one is found, adds
  a `MEDIUM`-capping confidence reason to that hour rather than silently averaging the two
  values together or picking one arbitrarily.

## Explicitly not vessel-hardcoded

Every threshold in the gates table and every deduction curve reads from `VesselProfile`
(`windTolerance`, `gustTolerance`, `waveTolerance`, `thunderstormTolerance`,
`visibilityTolerance`, plus `vesselType`/`length` informing the Small Craft Advisory
exemption). The MVP ships one sensible default recreational profile, but the scoring engine
itself has no motorcycle-style single-hardcoded-vehicle assumption anywhere in it — a
pontoon, a PWC, and a 34' cruiser run through the exact same functions with different
tolerance inputs.
