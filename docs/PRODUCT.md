# WakeWindow — Product Overview

> "WakeWindow" is a working product name only. It is used throughout this codebase for
> practical reasons (package identifiers, string resources, app label) but domain logic must
> never assume this name is permanent. Branding may change before release.

## What problem this solves

Recreational boaters currently have to visit five or six different sites — a general
weather app, `marine.weather.gov`, NDBC buoy pages, a tide chart, and a marina's own website
or Facebook page — before they can answer a simple question:

**"Is today a good day to take the boat out, and will I still be able to get back safely?"**

WakeWindow aggregates the data that answers that question, explains *why* the answer is what
it is, and always preserves a path back to the authoritative source. It is a **planning aid**,
not a chartplotter, and never claims to replace nautical charts, Notices to Mariners, Coast
Guard guidance, harbor authorities, or ordinary seamanship.

## Product pillars

WakeWindow's differentiation is that it plans the *outing*, not just the weather at a single
moment:

1. **LAUNCH** — where you're leaving from, and what that place can tell you (ramp fees,
   hours, fuel, VHF channel, restrictions).
2. **DEPARTURE** — conditions when you shove off.
3. **CONDITIONS ON THE WATER** — how things evolve for the whole time you're out, not a
   snapshot.
4. **RETURN** — conditions when you're expected back. A gorgeous morning followed by
   dangerous afternoon thunderstorms is not an "excellent" boating day, even though most
   commute-style weather apps would flatten it into one all-day average.
5. **MARINE CONDITIONS** — wind, waves, swell, tide, current, visibility, marine warnings —
   evaluated with marine-specific thresholds, not repurposed everyday-weather thresholds.
6. **PORT / LAUNCH INTELLIGENCE** — facility information kept structurally separate from
   weather, because a place-search API can tell you a marina exists, but not what its ramp
   fee, VHF channel, or gate hours are — that's separately-sourced, provenance-tracked
   local knowledge, and it can legitimately be missing.

Reducing WakeWindow to "a weather app with wave height" would erase the reason it exists.

## Two planning modes

### Mode A — Boating Day / Return to Same Launch (MVP)

The primary and first-built experience. The user picks a **launch location** (ramp, marina,
harbor, dock, yacht club, or other saved point), a **planned departure time**, and a
**planned return time**. WakeWindow evaluates the *entire* window — departure, underway, and
return — and never lets several good hours average away a dangerous return.

### Mode B — Port to Port / Trip Plan (architected now, built later)

Departure port, destination port, departure time, and optionally speed/duration/waypoints.
WakeWindow explicitly does **not** pretend to generate real marine routes by reusing
road-routing logic (a straight line between two ports routinely crosses land). Until real
marine routing/charting data is integrated, this mode is limited to user-defined waypoints or
an explicitly-labeled non-navigational planning corridor. The weather-sampling architecture
(`RouteSample`, role-based timing) is shared with Mode A and built to support N points along a
route from day one, so Mode B is additive, not a rewrite.

## Who this is for, and for what kind of water

WakeWindow is not ocean-only. It must degrade gracefully across:

- Ocean / offshore
- Coastal / nearshore
- Bay / inlet
- Intracoastal Waterway
- River
- Lake (no tide, no swell, no buoy — general weather + wind + storms only)
- Great Lakes

Missing categories of data (no tide station, no nearby buoy, inland location) reduce
**confidence** and are explicitly surfaced — they never cause the whole assessment to fail,
and no value is ever invented to fill a gap. "Not available" beats a guess.

## Vessel-aware, not one-boat-shaped

Boating suitability is much more vessel-dependent than motorcycle-commute weather. A 2 ft chop
that's a non-event for a 34' cruiser is a real hazard for a kayak or PWC. WakeWindow's scoring
engine is architected around a `VesselProfile` from day one so thresholds are configurable per
boat. The MVP shipped a single default profile; Sprint 3 added five selectable presets; Sprint 4
added a real vessel-profile editor letting a boater describe and save their actual boat — see
[VESSEL_PROFILES.md](VESSEL_PROFILES.md). Every threshold there is explicitly a *planning
preference*, never a manufacturer-rated safe operating limit.

## Facility data states

A launch's facility information (hours, fees, VHF channel, fuel, restrooms, and so on) can be
in one of four honest states, never collapsed into a single "unknown" or a guessed default:

- **Available** — verified present, with a source.
- **Not available** — verified absent (confirmed the marina has no fuel dock, say).
- **Unknown** — nobody has verified this fact yet. This remains the default for every field a
  wired-in source doesn't itself publish (see [DATA_SOURCES.md](DATA_SOURCES.md) "Marine place
  / launch intelligence" — Sprint 4 wired Florida FWC boat ramps specifically, everything else
  is still unverified), and it is a genuinely different claim from "not available" — a launch
  with "Unknown" fuel availability might well have fuel; WakeWindow simply hasn't confirmed it.
- **Not applicable** — the concept doesn't apply here at all (e.g. "transient slips" at a bare
  boat ramp with no docking).

`LaunchInfoScreen` renders every field's actual state plainly rather than hiding fields with no
data or inventing a plausible-sounding default — a launch with entirely unverified facility
data is not a broken screen, it's an honest one. See [DATA_SOURCES.md](DATA_SOURCES.md)
"Marine place / launch intelligence" for the provenance model behind this.

## Safety posture

WakeWindow is a decision-support and planning tool. It is explicitly **not**:

- a chartplotter or navigational instrument,
- a replacement for nautical charts, Notices to Mariners, USCG guidance, or local harbor
  authority information,
- a substitute for the boat operator's own judgment and seamanship.

This is communicated with concise in-context language (e.g. next to a boating assessment) and
in full in a dedicated Safety/About section — not as intrusive legal text on every screen. See
the `SafetyDisclaimer` seam described in [ARCHITECTURE.md](ARCHITECTURE.md).

## Explainability is a product requirement, not a nice-to-have

Every assessment must be able to answer "why?" A `CAUTION` rating is useless without knowing
*which* hazard drove it and *when* it occurs. See [MARINE_SCORING.md](MARINE_SCORING.md) for
how this is structured.

## Relationship to RideCast

WakeWindow is a new product built for Inknaut Labs, in the same family as RideCast, Abra, and
NextUp. RideCast (motorcycle-commute weather suitability) is a **reference implementation**,
not a starting point to rename or reskin. See
[RIDECAST_REFERENCE_AUDIT.md](RIDECAST_REFERENCE_AUDIT.md) for the concept-by-concept decision
of what was reused, adapted, rewritten, or deliberately rejected, and
[ARCHITECTURE.md](ARCHITECTURE.md) for how WakeWindow's own architecture is organized.
