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
| Active Hurricane/Tropical Storm Warning, or Special Marine Warning in effect at that hour | NWS alert feed | `NO_GO` |
| Active Gale Warning | NWS alert feed | `POOR` (vessel size does not exempt a vessel from a gale) |
| Active Small Craft Advisory | NWS alert feed | `CAUTION`, unless the vessel's tolerances (length/type) indicate it is not "small craft," in which case it's a deduction, not a gate |
| Thunderstorm probability ≥ vessel's `thunderstormTolerance` | Forecast | ≥ tolerance → `CAUTION`; ≥ tolerance + 30 pts → `POOR`/`NO_GO` |
| Wave height ≥ 1.0× vessel's `waveTolerance` | Forecast/observation | `CAUTION` at 1.0×, `POOR` at 1.25×, `NO_GO` at 1.5× |
| Gust ≥ vessel's `gustTolerance` | Forecast/observation | `CAUTION` at tolerance, `POOR` at tolerance + 10 kt |
| Visibility below 1 NM | Forecast/observation | `CAUTION` below 1 NM, `POOR` below 0.5 NM |

A gate is a floor on the *category*, not a fixed penalty on the *score* — the brief is
explicit that a severe marine warning "should not merely subtract 15 points from an
otherwise pleasant day."

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
(extended across the full daylight/available-forecast range, independent of the user's chosen
departure/return so it can suggest "you asked about 8–4, but 7:30–1:30 is actually your best
window") for the longest contiguous span where every hour scores `GOOD` or better, breaking
ties by highest average score, and it carries the specific reasons the boundary hours fall
where they do (e.g., "storms become more likely after 3 PM" is the actual hazard threshold
being crossed at hour 15, not a canned phrase).

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
weather only"), never just a bare confidence label.

## Graceful degradation (inland water, sparse data)

A location with no tide station, no marine buoy, and no NWS marine grid coverage (e.g., an
inland lake) still produces a `BoatingWindowAssessment` — built from whatever
`GeneralWeatherProvider`/wind/thunderstorm data is available, with every marine-only field
left `null` in `MarineConditions`, and `Confidence` explicitly reflecting the missing
categories. It never fails outright, and it never invents a wave height or tide time that
doesn't exist for that water body.

## Explicitly not vessel-hardcoded

Every threshold in the gates table and every deduction curve reads from `VesselProfile`
(`windTolerance`, `gustTolerance`, `waveTolerance`, `thunderstormTolerance`,
`visibilityTolerance`, plus `vesselType`/`length` informing the Small Craft Advisory
exemption). The MVP ships one sensible default recreational profile, but the scoring engine
itself has no motorcycle-style single-hardcoded-vehicle assumption anywhere in it — a
pontoon, a PWC, and a 34' cruiser run through the exact same functions with different
tolerance inputs.
