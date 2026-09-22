# Session 4 done-when verified live; scheme allowlist wired

Two gaps closed after re-checking PLAN.md's guardrail table and Week 1
Session 4 line by line against the actual code, rather than trusting
memory of an older state.

## Correction to an earlier read of the codebase

An initial grep for `CompletableFuture`/`Semaphore` across
`retrieval-service` came back empty and looked like Session 4's
concurrency set (parallel fetch, `Semaphore(4)`, single-flight lock) had
never been built. That was a false negative from a zsh glob issue in the
grep invocation, not a real gap -- reading `ExtractService` and
`SearchService` directly showed all three are fully implemented:
`CompletableFuture.supplyAsync` + `exceptionally()` per URL in
`ExtractService.extract()`, `Semaphore` gating the extractor sidecar,
and `SET key val NX PX 30000` (`setIfAbsent` + `LOCK_TTL`) plus a poll
loop in `SearchService.search()` for the thundering-herd case.

## What was actually missing and is now closed

**1. PLAN's Session 4 "done when" had never been run.** The code existed
but nobody had hammered `/api/v1/search` with 20 concurrent identical
queries to prove the single-flight lock actually collapses them. Ran it
live against the warm `retrieval-service`:
```
20 parallel POST /api/v1/search, identical body
-> creditsSpent summed across all 20 responses = 1
-> cacheHits summed across all 20 responses    = 19
-> GET /api/v1/quota before/after: exactly 1 credit spent
```
Matches PLAN's line exactly: "exactly one upstream call, 19 cache hits,
no rate-limit breach."

**2. Guardrail item 3 ("fetch hardening") was half-built.** Timeouts and
the private-network block existed; the scheme allowlist
(`guardrails.retrieval.allowed-schemes: [http, https]`) was in
`docs/PLAN.md`'s config tree but had no matching field on
`GuardrailProperties.Retrieval` and no check anywhere in `PageFetcher`.

- Added `List<String> allowedSchemes` to `GuardrailProperties.Retrieval`
  (`common`) and `allowed-schemes: [http, https]` to
  `retrieval-service/application.yml`.
- `PageFetcher.isAllowedScheme()` checks the parsed scheme against the
  list, same unconditional-when-enabled pattern as the private-network
  check right below it. New status: `BLOCKED_SCHEME`, never cached, same
  reasoning as `UNREACHABLE`/`TOO_LARGE`.
- **One correction made before this shipped:** the first version treated
  a URI parse failure the same as a disallowed scheme, which broke the
  existing `malformedUrlIsUnreachable` test -- a malformed URL used to
  fall through to the real fetch's own `URI.create` and come back
  `UNREACHABLE`. Fixed so a parse failure returns `true` (allowed) from
  `isAllowedScheme` and defers to the existing malformed-URL path; the
  scheme check only fires when the URL parses cleanly but names a scheme
  outside the allowlist.
- `PageFetcherTest` gained `disallowedSchemeIsBlocked` (12th test in that
  class); `QuotaServiceTest` and `RunUsageGuardTest` needed their direct
  `GuardrailProperties.Retrieval(...)` constructor calls updated for the
  new component. Full `common`+`retrieval-service`+`agent-service` suite:
  119/119 green.
- Live-verified two ways against the restarted service: `file:///etc/passwd`
  (a hostless URL) never reaches `PageFetcher` at all -- `ExtractCacheKey.of()`
  throws inside `UrlNormalizer.normalize()` first, and the batch's
  `.exceptionally()` reports it as `UNREACHABLE` before the scheme check
  runs. `ftp://example.com/file.txt` (a URL with a host, wrong scheme) does
  reach `PageFetcher` and correctly comes back `BLOCKED_SCHEME`.

Restarting `retrieval-service` for this hit one build trap: `mvn -pl
retrieval-service spring-boot:run` alone picked up a stale `common.jar`
from the local repo and failed with `NoSuchMethodError:
GuardrailProperties$Retrieval.allowedSchemes()` -- `-pl` without `-am`
doesn't rebuild the upstream module you just changed. Fixed with `mvn -pl
common install -DskipTests` first.

## Remaining gap, left open

The `file://` case above is a real (pre-existing, not introduced here)
gap: a hostless disallowed-scheme URL is reported as `UNREACHABLE`
instead of `BLOCKED_SCHEME`, because `ExtractCacheKey.of()` runs before
`PageFetcher` ever sees the URL and throws on any hostless URL regardless
of scheme. The externally observable guardrail still holds -- the URL is
never fetched either way -- but the status code is misleading for
diagnosis. Not fixed here: reordering the scheme check ahead of the cache-key
computation in `ExtractService.extractOne()` touches the fetch pipeline's
control flow, which is worth its own pass rather than a rushed fix
bundled into this one.
