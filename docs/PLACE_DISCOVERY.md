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
`fallback` (Photon) concurrently, then:

1. Concatenates all `boatingSources` results first, `fallback` results last -
   `FWC_BOAT_RAMP` and `USACE_RECREATION_AREA` always rank ahead of `GEOCODING`, regardless of
   fetch order or how the individual providers internally order their own results.
2. Drops any `GEOCODING` candidate within 0.25 NM of an authoritative-source candidate, treating
   it as the same real-world place rather than showing a ramp twice under two different names/
   sources.
3. Reports the whole search as failed only when **every** configured source failed - one dead
   source alongside others that succeeded (even with zero results) is not a search failure, the
   same permissive-fan-out pattern `DefaultBoatingRepository` already uses for weather
   providers.

Confirmed live (see [ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md)): a search for "Port
Canaveral" ranks the real FWC boat ramp first, ahead of seven Photon matches including an
unrelated result in British Columbia; a search for "Clinton Lake" ranks the real USACE
recreation area first, ahead of four Photon matches for different "Clinton Lake"s in other
states.

## UI honesty

`LaunchSearchScreen` shows an overline on every result: `"<type> · <provenance>"` -
`"Boat ramp · FWC verified"`, `"Place · USACE recreation area"`, or `"Marina · unverified"` for
a Photon guess. This is the concrete implementation of "honest search-result UI showing place
type without inferring facility data" - a `GEOCODING`-sourced type guess is never presented with
the same visual confidence as a government inventory match.

## What this is not

This is still place *discovery* (name, coordinates, address, a type claim with honest
provenance) - it is not [`MarineFacilityInfo`](DATA_SOURCES.md) (ramp fees, VHF channel, gate
hours, lane count). `MarineFacilityInfoProvider` remains unimplemented; FWC's own data actually
includes richer per-ramp fields (`RampType`, `AccessType`, `TotalLanes`, `Amenities`,
`ContactPhone`, fee info) that `FwcMapper` currently discards down to a `MarinePlaceCandidate` -
a real, scoped opportunity for a future sprint to wire FWC in as a genuine
`MarineFacilityInfoProvider` for Florida launches specifically, without needing a general-purpose
scraper.
