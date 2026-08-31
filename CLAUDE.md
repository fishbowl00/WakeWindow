# WakeWindow — Development Rules

Adapted from RideCast's own `CLAUDE.md` (see docs/RIDECAST_REFERENCE_AUDIT.md) — the
discipline transfers even though the product doesn't.

- Implement only the task explicitly requested. Do not implement future roadmap items
  (see docs/ROADMAP.md) speculatively.
- Do not add a dependency unless the current task actually needs it.
- Keep `domain/` free of `android.*`/`androidx.*` imports, always — it's what keeps scoring,
  consensus, and mapping logic fast to unit-test and ready for an eventual `:shared` KMP
  module split without a rewrite.
- Marine scoring thresholds live in `VesselProfile`, never as a hardcoded constant in the
  scoring engine — see docs/MARINE_SCORING.md.
- Never invent a data value when a provider has none. A null field renders as "Not
  available"; it is never filled with a plausible-looking default.
- If an architecture change is significant (a new provider category, a new persisted
  entity shape, a scoring-model change), say so and explain why before making it.
- Read docs/PRODUCT.md, docs/ARCHITECTURE.md, docs/MARINE_SCORING.md, and
  docs/DATA_SOURCES.md before touching the layer they describe.

## Definition of done

- `./gradlew compileDebugKotlin testDebugUnitTest` passes.
- New domain logic has unit tests (JUnit4, hand-written fakes, no MockK/Mockito — see the
  existing `app/src/test/java/com/wakewindow/app/domain/**` tests for the pattern).
- No unrelated files changed.
- docs/ROADMAP.md updated if a roadmap item's status changed.
