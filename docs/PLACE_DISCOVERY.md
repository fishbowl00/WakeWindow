# WakeWindow — Place Discovery

Sprint 2 shipped Photon (keyless OpenStreetMap geocoding) as the only place-search source, with
a known limitation: it returns generic geocoding matches with no boating-specific authority, and
guesses `MarinePlaceType` from OSM tags that may not exist or may be wrong. Sprint 3 adds two
real, government-sourced, boating-relevant discovery providers and ranks them ahead of Photon,
without removing Photon as the broad-coverage fallback. See
[DATA_SOURCES.md](DATA_SOURCES.md) for the underlying API details and
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md) for live search results against both
providers.

## Sources

| Source | `PlaceSourceType` | What it actually is | `MarinePlaceType` it can honestly claim |
|---|---|---|---|
| Florida FWC Boat Ramp Inventory | `FWC_BOAT_RAMP` | A real, government-maintained statewide inventory of public-access boat ramps (`gis.myfwc.com`, ArcGIS REST) | `BOAT_RAMP` - this is exactly what the dataset is |
| USACE recreation areas | `USACE_RECREATION_AREA` | Land-parcel polygons around Corps-managed reservoirs (`services7.arcgis.com/.../usace_recreation_areas`) | `OTHER` - **never** `BOAT_RAMP`. This dataset identifies that a Corps recreation area exists, not whether it has a launch ramp; most Corps reservoirs do have one somewhere, but this data doesn't say where, and claiming `BOAT_RAMP` from it would be exactly the kind of unearned inference this sprint was asked to eliminate |
| Photon / OpenStreetMap | `GEOCODING` | Crowd-sourced, keyless geocoding - the fallback, not an authority | Whatever `PhotonPlaceProvider.guessType()` infers from OSM tags - always was, and remains, a guess |

Both new sources are ArcGIS REST `query` endpoints hit with a `where` clause built from the
user's search text (`FwcMapper.whereClauseFor` / `UsaceMapper.whereClauseFor`) - user input is
escaped (`'` doubled) before being interpolated into the clause, since it's a real SQL-like
injection surface even though the endpoint is read-only. Neither is bias/location-scoped
currently (the UI's search box doesn't pass a `bias` coordinate), so both use a pure text match
against name/water-body/city/county fields.

**FWC coverage is Florida-only** - a structural fact about the source data, not a bug. A ramp
reported with a `Status` containing "closed," "removed," or "destroyed" is excluded from
results entirely (`FwcMapper`) - recommending a launch as if it were operational when the
state's own inventory says otherwise would itself be an unearned inference.

**USACE data is polygon land parcels, not points.** `UsaceService.search()` requests
`returnCentroid=true` so ArcGIS computes a representative point per polygon server-side, rather
than doing polygon math client-side. A single reservoir is typically split across many small
parcels, all sharing the same `RECPROJECTSITENAME` - `UsaceMapper.mapCandidates()` collapses
these to one result per distinct site name (keeping the first/nearest-ranked parcel) rather than
showing the same lake five times.

## Ranking

`CompositeMarinePlaceProvider` fans out to every configured `boatingSources` provider plus the
`fallback` (Photon) concurrently, deduplicates (see below), then ranks three-tiered:

1. **Source authority** - `FWC_BOAT_RAMP` and `USACE_RECREATION_AREA` always rank ahead of
   `GEOCODING`, regardless of fetch order, how the individual providers order their own
   results, or physical proximity - an exact named ramp is never buried under a physically
   closer but unverified geocoder match.
2. **Boating relevance of the place type**, within a tier - `BOAT_RAMP` > `MARINA` >
   `HARBOR`/`PORT` > `DOCK`/`ANCHORAGE`/`YACHT_CLUB` > `OTHER` (Sprint 4). This mostly matters
   within the `GEOCODING` tier, since FWC only ever yields `BOAT_RAMP` and USACE only ever
   yields `OTHER`.
3. **Proximity to an optional `bias` point**, only within the same tier and type (Sprint 4) -
   see "Location bias" below. With no bias, this tier is a no-op and relative order is
   unaffected, exactly as before Sprint 4.

Reports the whole search as failed only when **every** configured source failed - one dead
source alongside others that succeeded (even with zero results) is not a search failure, the
same permissive-fan-out pattern `DefaultBoatingRepository` already uses for weather providers.

Confirmed live in Sprint 3 (see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md), not
re-verified live in Sprint 4 - this session's environment has no outbound network access, see
the sprint report): a search for "Port Canaveral" ranks the real FWC boat ramp first, ahead of
seven Photon matches including an unrelated result in British Columbia; a search for "Clinton
Lake" ranks the real USACE recreation area first, ahead of four Photon matches for different
"Clinton Lake"s in other states.

## Location bias (Sprint 4)

`MarinePlaceProvider.search()` always accepted an optional `bias: GeoPoint?`, but nothing
supplied one until this sprint. `WakeWindowViewModel.searchBias()` now resolves it from the
currently-active plan's launch, or otherwise the most recently saved launch - **never** device
GPS location. Device/GPS bias was deliberately not implemented this sprint: it would need a new
location-services dependency (Google Play Services `FusedLocationProvider` or similar,
currently not a project dependency - see `CLAUDE.md`'s "don't add a dependency unless the
current task actually needs it") and a runtime-permission flow that this session has no way to
verify without a physical device or emulator. A saved-launch-derived bias needs neither, and is
explicitly listed as an acceptable alternative in the sprint brief ("previous/saved-launch
region bias"). With zero saved launches, search remains unbiased text relevance only, exactly
as before - **location bias is never required to search.**

## Duplicate reconciliation (Sprint 4: generalized beyond geocoding)

Sprint 3's dedup only ever dropped a `GEOCODING` candidate that landed within 0.25 NM of an
authoritative-source candidate. `CompositeMarinePlaceProvider.dedupe()` generalizes this: any
candidate within 0.25 NM of an *already-kept, differently-sourced* candidate is dropped,
regardless of which two sources they came from - so FWC and USACE describing the same physical
site (a real possibility, not yet observed live) also collapses to one result, keeping
whichever source ranks higher. **Deliberately never applied within a single source's own
results** - two genuinely distinct ramps from the same authoritative inventory sitting close
together (e.g. two launches at one park) must never collapse into one just because that
source's own data places them near each other; this is the "don't overmerge unrelated
facilities that happen to sit beside each other" caution the sprint brief calls out explicitly.
Name-based matching (beyond distance) was considered and deliberately not added this sprint -
without live data to tune a normalized-name-similarity threshold against, a distance-only rule
stays the conservative, defensible choice; loosening it to also require name overlap (making it
*more* conservative, not less) is a reasonable follow-up once real duplicate cases are observed.

## UI honesty

`LaunchSearchScreen` shows an overline on every result: `"<type> · <provenance>"` -
`"Boat ramp · FWC verified"`, `"Place · USACE recreation area"`, or `"Marina · unverified"` for
a Photon guess. This is the concrete implementation of "honest search-result UI showing place
type without inferring facility data" - a `GEOCODING`-sourced type guess is never presented with
the same visual confidence as a government inventory match.

## What this is not

This is still place *discovery* (name, coordinates, address, a type claim with honest
provenance) - it is not, by itself, [`MarineFacilityInfo`](DATA_SOURCES.md) (ramp fees, VHF
channel, gate hours, lane count). As of Sprint 4, `FwcFacilityInfoProvider` is a real
`MarineFacilityInfoProvider` implementation for Florida FWC boat ramps specifically (see
[DATA_SOURCES.md](DATA_SOURCES.md) "Sprint 4 — `FwcFacilityInfoProvider`") - a search result's
`MarinePlaceCandidate.sourceId` (FWC's `OBJECTID`, when present) is what lets `LaunchInfoScreen`
re-fetch that facility record. USACE and Photon results still carry no facility intelligence;
`FwcFacilityInfoProvider` honestly reports `NoDataAvailable` for anything that didn't come from
`FWC_BOAT_RAMP`, never a guess.
