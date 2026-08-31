# WakeWindow — Cache Policy

Sprint 3's roadmap flagged provider fan-out as the main scale risk (see
[ROADMAP.md](ROADMAP.md) "Scale and Provider Risk") and named the fix: "a durable (Room-backed),
location-keyed cache with a TTL suited to each data type's real freshness needs." Sprint 4 adds
that cache — scoped honestly, not applied everywhere at once.

## Architecture

`data/cache/DurableCache.kt` — a small, generic TTL cache in front of a suspend fetch:

```kotlin
class DurableCache(private val store: CacheStore, private val now: () -> Instant = Instant::now) {
    suspend fun <T> getOrFetch(
        key: String, ttl: Duration,
        serialize: (T) -> String, deserialize: (String) -> T,
        allowStaleOnFetchFailure: Boolean = false,
        fetch: suspend () -> T,
    ): T
}
```

- `CacheStore` is the storage seam (`get`/`put`/`delete`/`deleteExpired`) — `RoomCacheStore`
  (backed by `CacheEntryEntity`, a plain `key`/`payload`/`fetchedAt`/`expiresAt` table) is the
  real implementation; an in-memory fake is used in tests, matching every other provider split
  in this codebase.
- `payload` is an opaque, caller-serialized string. `DurableCache` never knows the shape of
  what it's caching — each caller supplies its own `serialize`/`deserialize`, kept in the
  same data-layer package as the provider it caches (see `FwcFacilityCacheCodec`,
  `PlaceSearchCacheCodec`) rather than annotating a `domain/` type with
  `kotlinx.serialization`.
- A fetch failure propagates by default (`allowStaleOnFetchFailure = false`) — a caller that
  wants "serve stale on error" opts in explicitly per call. No caller currently opts in; this
  exists for a future safety-conscious use, not used blindly.
- Only a genuinely successful fetch is ever cached. A "no data" or "failed" outcome is never
  remembered as if it were a durable fact — see `FwcFacilityInfoProvider` and
  `CachedMarinePlaceProvider` for how each unwraps its own sealed outcome type around the
  cache boundary specifically to keep that true.

`data/cache/RequestCoalescer.kt` — de-duplicates *concurrent* in-flight requests for the same
key within one process (a `Mutex`-guarded `key -> Deferred` map). This is a different problem
from `DurableCache` (which addresses *repeat* requests over time): two near-simultaneous
callers for the same key share one fetch instead of issuing two. Backed by a process-lifetime
`CoroutineScope` (`AppDependencies.applicationScope`, `SupervisorJob + Dispatchers.IO`) so a
coalesced request outlives the single screen call that happened to start it.

## What's wired in this sprint

| Data | Cache key prefix | TTL | Rationale |
|---|---|---|---|
| FWC facility info (`FwcFacilityInfoProvider`) | `fwc_facility:` | 7 days | Ramp facility details (lanes, phone, amenities, ramp/access type) change on the timescale of a physical renovation or a records update, not minute to minute. |
| Place search results (`CachedMarinePlaceProvider`, wrapping the FWC/USACE/Photon fan-out) | `place_search:` | 15 min | Cheap to refresh and a stale search result is a far smaller problem than stale marine safety data, but a popular query (e.g. "Port Canaveral") re-fetched on every keystroke/recomposition is real, avoidable cost. Request coalescing is layered on top of the same cache, so simultaneous identical searches share one fetch even within the 15-minute window. |

Both are wired through `AppDependencies` with one shared `DurableCache` instance (one Room
table, one process-lifetime coalescer scope) rather than one per call site.

## What's documented but NOT wired this sprint (an honest gap, not silently skipped)

The full assessment fan-out (`DefaultBoatingRepository.buildAssessment()` — NWS forecast,
alerts, Open-Meteo, CO-OPS tide/current, NDBC observation) is **not** wrapped in `DurableCache`
this sprint. Rationale for each, so this is a real decision and not an oversight:

| Data | Would-be TTL | Why not wired yet |
|---|---|---|
| NWS forecast (general + marine) | Moderate (~30-60 min) | Serializing `MarineConditions`/`ForecastOutcome` durably is a real lift (many nullable numeric fields, `SourceReference`, `Confidence`) better done as its own reviewed change than folded into an already-large sprint, given this session had no way to compile/test the result. |
| NWS alerts | Short (~5-10 min) | **Safety-critical** — see docs/ASSESSMENT_VALIDATION.md "Missing data policy." A stale alert cache is a materially worse failure mode than a slow refetch; this needs its own careful stale-handling review before it's cached at all, not a default TTL applied uniformly. |
| NDBC observations | Short (~10 min, matching the existing in-memory snapshot cache) | Already has a narrower, already-shipped in-memory 10-minute cache (`NdbcObservationProvider`, Sprint 2/3) that serves the same purpose for the common case (one process, repeated reads of the same network-wide snapshot); promoting it to the shared durable cache is a mechanical follow-up, not attempted here. |
| CO-OPS station metadata | Very long (days) | Already has a process-lifetime in-memory cache (`CoopsTideProvider`'s station list) for the same reason as NDBC above. |
| Tide/current predictions | Longer (hours) — deterministic predictions, not live readings | Not yet wired; same rationale as the NWS forecast row (real serialization work, safer as a follow-up). |
| Real facility/USACE metadata beyond FWC | Days | No second facility-intelligence source exists yet to cache (see [DATA_SOURCES.md](DATA_SOURCES.md)). |

**Never cache with a stale-tolerant policy:** live buoy observations and marine alerts, without
a specific, reviewed staleness story — see the table above and
[ASSESSMENT_VALIDATION.md](ASSESSMENT_VALIDATION.md).

## Testing

`DurableCacheTest` covers hit/miss/expiry/failure-propagation/stale-fallback-opt-in/
invalidation/key-isolation using an in-memory `CacheStore` fake. `RequestCoalescerTest` proves
concurrent same-key calls share one fetch, different keys never coalesce, and sequential calls
(no overlap) each fetch independently. `FwcFacilityInfoProviderTest` and
`CachedMarinePlaceProviderTest` prove the *wired* behavior end to end: a cache hit skips the
network call, and a failure/no-data result is never cached.
