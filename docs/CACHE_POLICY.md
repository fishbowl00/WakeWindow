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

## What's wired

| Data | Cache key prefix | TTL | Rationale |
|---|---|---|---|
| FWC facility info (`FwcFacilityInfoProvider`) | `fwc_facility:` | 7 days | Ramp facility details (lanes, phone, amenities, ramp/access type) change on the timescale of a physical renovation or a records update, not minute to minute. |
| Place search results (`CachedMarinePlaceProvider`, wrapping the FWC/USACE/Photon fan-out) | `place_search:` | 15 min | Cheap to refresh and a stale search result is a far smaller problem than stale marine safety data, but a popular query (e.g. "Port Canaveral") re-fetched on every keystroke/recomposition is real, avoidable cost. Request coalescing is layered on top of the same cache, so simultaneous identical searches share one fetch even within the 15-minute window. |
| NWS/Open-Meteo general forecast (`CachedGeneralWeatherProvider`, Sprint 5) | `nws_forecast_general:` | 45 min | A forecast changes on the timescale of a model run, not minute to minute, but a multi-hour-old cached value would go stale during a live planning session. |
| NWS/Open-Meteo marine forecast (`CachedMarineForecastProvider`, Sprint 5) | `nws_forecast_marine:` | 45 min | Same rationale as general forecast; kept as a distinct key prefix so a caller can want general data without marine data (an inland lake) or vice versa even when both wrap the same underlying `NwsProviders` instance. |
| NWS marine alerts (`CachedMarineAlertProvider`, Sprint 5) | `nws_alerts:` | 7 min | **Safety-critical** — deliberately the shortest TTL in the whole cache, inside the sprint brief's documented 5-10 minute range, and never opts into `allowStaleOnFetchFailure` (a fetch failure always propagates exactly as an uncached call would) — see "Safety-critical alert caching" below for the full review this sprint did before wiring it. |
| CO-OPS tide station lookup (`CachedTideProvider`, Sprint 5) | `coops_tide_station:` | 7 days | Station identity/location/datum is pure geography and essentially never changes. |
| CO-OPS tide predictions (`CachedTideProvider`, Sprint 5) | `coops_tide_events:` | 6 hours | Deterministic harmonic predictions, not live readings — re-fetching the same station/date repeatedly across one planning session (especially a multi-waypoint trip) is pure waste. |
| CO-OPS current station lookup / predictions (`CachedCurrentProvider`, Sprint 5) | `coops_current_station:` / `coops_current_events:` | 7 days / 6 hours | Identical rationale to the tide rows above. |

All are wired through `AppDependencies` with one shared `DurableCache` instance (one Room
table, one process-lifetime coalescer scope) rather than one per call site — and, as of Sprint
5, shared between `boatingRepository(context)` (Mode A) and `tripBoatingRepository(context)`
(Mode B), so a trip plan through an already-planned launch benefits from that launch's own
recently-cached forecast/tide/current data instead of re-fetching independently.

### Request coalescing for trip mode

Every Sprint-5-wired provider above is also request-coalesced (`RequestCoalescer`, per-provider-
instance), not just cached. This matters specifically for trip mode: `DefaultTripBoatingRepository`
fans out one concurrent fetch per trip point, and two points that round to the same NWS grid
coordinate/window or the same nearest CO-OPS station would otherwise both miss the cache
*simultaneously* (the first hasn't written its result yet when the second starts) and issue two
identical network calls. Coalescing collapses simultaneous identical requests into one — proven
in `CachedProvidersTest` ("repeated general forecast query... hits the cache," "repeated tide
station lookups... never duplicate the underlying station query," "concurrent forecast requests
for the same key coalesce into a single underlying fetch").

### Safety-critical alert caching — the review this sprint did before wiring it

Sprint 4 deliberately left NWS alerts uncached, flagging that a stale alert cache is a materially
worse failure mode than a slow refetch and needed its own review first. Sprint 5's review
concluded: cache **is** safe here, provided two rules hold, both enforced in
`CachedMarineAlertProvider`:

1. The TTL must be short enough that "stale" and "fresh" are barely distinguishable in practice
   — 7 minutes, not the moderate 45-minute TTL forecast data gets.
2. A fetch *failure* must never fall back to a cached value, stale or otherwise —
   `allowStaleOnFetchFailure` stays `false`, so a failed alert check always propagates to
   `DefaultBoatingRepository`/`DefaultTripBoatingRepository`'s own existing "a failed alert
   check is NOT the same fact as zero active alerts" handling, exactly as an uncached call
   would. Caching a *successful* zero-alerts result for 7 minutes is a reasonable, bounded risk;
   silently serving a stale result after a real fetch failure is not, and this cache never does.

## What's documented but NOT wired (an honest gap, not silently skipped)

| Data | Would-be TTL | Why not wired |
|---|---|---|
| NDBC observations | ~10 min | Already has a narrower, already-shipped in-memory 10-minute snapshot cache (`NdbcObservationProvider`, Sprint 2/3) that serves the common case. **Specific finding from this sprint's review, not just "not attempted":** `MarineObservationOutcome.Success`'s `SelectedMarineStation.ageMinutes`/`MarineConditions.observationAgeMinutes` are computed once, at fetch time, relative to "now." A durable-cache hit served minutes later within the same TTL window would silently return a payload whose age fields no longer reflect the actual elapsed time — a real correctness risk for exactly the kind of live-observation freshness labeling `ObservationFreshness`/`ObservationalCautionEvaluator` depend on. Wiring this correctly needs either re-deriving age at read time (not fetch time) or a much shorter, freshness-safe TTL — real, scoped follow-up work, not a default-TTL mechanical wrap like the other providers got. |
| Real facility/USACE metadata beyond FWC | Days | No second facility-intelligence source exists yet to cache (see [DATA_SOURCES.md](DATA_SOURCES.md)). |

**Never cache with a stale-tolerant policy:** live buoy observations (not durably cached at all,
per above) and marine alerts (cached, but never stale-on-failure — see "Safety-critical alert
caching" above).

## Testing

`DurableCacheTest` covers hit/miss/expiry/failure-propagation/stale-fallback-opt-in/
invalidation/key-isolation using an in-memory `CacheStore` fake. `RequestCoalescerTest` proves
concurrent same-key calls share one fetch, different keys never coalesce, and sequential calls
(no overlap) each fetch independently. `FwcFacilityInfoProviderTest` and
`CachedMarinePlaceProviderTest` prove the Sprint 4 wired behavior end to end: a cache hit skips
the network call, and a failure/no-data result is never cached. `CachedProvidersTest` (Sprint 5)
proves the same for every newly-wired provider, plus coalescing specifically.
