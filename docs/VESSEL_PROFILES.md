# WakeWindow — Vessel Profiles

See [MARINE_SCORING.md](MARINE_SCORING.md) "Vessel profiles" for how a `VesselProfile`'s
tolerances flow into scoring. This document covers the profile model and UI itself.

## Model

`VesselProfile` (`domain/vessel/VesselProfile.kt`) carries:

- Identity: `name`, `id` (a preset's `id` is simply its own `name`; a custom profile gets a
  real UUID via `withNewId()`), `isCustom`, `notes`, `createdAtEpochMillis`/`updatedAtEpochMillis`.
- Description: `vesselType`, `lengthFt`, `beamFt`, `draftFt`, `propulsionType`, `cruiseSpeedKts`
  — all optional; nothing about the boat itself is required to use the app.
- Scoring inputs: `windToleranceKts`, `gustToleranceKts`, `waveToleranceFt`,
  `thunderstormTolerancePercent`, `visibilityToleranceNm`, `isSmallCraft`.

`VesselProfile.presets()` returns the five built-in profiles unchanged from Sprint 3. Nothing
about a preset is stored in Room — it's a compile-time constant list, resolved by `id` (its
name) wherever a profile needs to be looked up.

## Persistence

A **custom** profile (`VesselProfileEntity`, `data/local/VesselProfileEntity.kt`) is Room-backed
and supports multiple saved profiles from the start — there is no single hardcoded "the custom
profile" slot, even though the first UI (`VesselProfileScreen`) only surfaces editing one at a
time. `VesselPreferenceStore` remembers only which profile ID is currently *selected*
(`SharedPreferences`, one scalar) — the full records live in Room.

`RoomVesselProfileRepository` implements the small `VesselProfileRepository` domain interface
(`getAllCustom`/`save`/`delete`), matching the split already used for saved launches.

## UI: `VesselProfileScreen`

Reachable from the plan screen's vessel row ("Customize"/"Edit"). Lets a boater:

- pick a starting point (any preset or already-saved custom profile),
- rename it, set type/length,
- edit the scoring-input tolerances directly (an "Advanced"-style card, not hidden behind a
  separate screen, but visually and textually distinct from the identity fields above it),
- save as a new profile, update an existing one, reset back to the preset it started from, or
  delete a saved profile.

The first edit away from an unmodified preset assigns the draft a fresh, real ID immediately
(`VesselProfile.markCustomized()`), not merely a flipped `isCustom` flag — otherwise a saved
profile could end up reusing a preset's `id` (its own name) and silently collide with it in
`WakeWindowUiState.availableVessels` and in ID-based lookups.

## Planning preferences, not safe limits

Every tolerance field in the UI is labeled and described as a **planning preference** /
**comfort threshold** — never "safe limit," "safe operating limit," or anything implying a
seaworthiness certification. `PlanningPreferencesCard`'s own on-screen text states explicitly:
these values don't account for loading, hull condition, or skipper experience, and an official
marine warning always takes precedence over a favorable vessel preference regardless of how
generously these are set. This is the same "gate can only pull the category down" rule
[MARINE_SCORING.md](MARINE_SCORING.md) documents for alert gates in general — a custom profile
cannot open a path around it; it can only change where the wind/wave/gust *deduction* curves
sit, never bypass a hard gate.

## What isn't built

- No per-vessel "active" selection independent of the single `WakeWindowUiState.vessel` field —
  there is one active vessel for the whole app at a time, not a per-launch default. Architected
  for later (each profile already has a stable `id`) but not built this sprint.
- No fuel-range or towing/vehicle-compatibility fields — explicitly out of scope (see
  docs/ROADMAP.md's backlog).
