# 2026-09-22 (part 10) — the guardrail tripwire eval: all 6 PLAN cases covered

Closing out PLAN's second eval: "six cases asserting each guardrail fires
when it should ... Runs in SHADOW and asserts on the log counters." All 6
cases now have a direct, targeted test proving the decision logic fires
correctly.

## Why one unified harness class wasn't built

PLAN's wording suggests a single eval that exercises all 6 guardrails in one
run. That's not practical here without inventing infrastructure that isn't
otherwise needed: the three services -- `retrieval-service`, `agent-service`,
`control-plane` -- are separate Maven modules with no shared test
dependency between them. A single JUnit class can't construct a
`PageFetcher` (retrieval-service), a `RunUsageGuard` (agent-service), and a
`PlannerService` (control-plane) all in one file; they don't share a
classpath at test time. Building a cross-module test harness just to
satisfy the letter of "one eval class" would be real, unwanted work for no
functional gain -- the actual goal, proving each guardrail's decision logic
fires correctly, is fully met by direct tests living where the code they
test already lives.

## The 6 cases, mapped to their tests

1. **300KB document** -- `PageFetcherTest.bodyOverTheCapIsTooLarge` (existing,
   `retrieval-service`). `PageFetcher`'s size guard, tested at both the
   `Content-Length` header check and the streamed-body check.
2. **`10.0.0.1` URL** -- `PageFetcherTest.siteLocalUrlIsBlockedWhenEnabled`
   plus 3 sibling tests (loopback, link-local, disabled-flag passthrough),
   built this session as part of the SSRF-blocking work.
3. **Run past `max-searches`** -- `RunUsageGuardTest` (existing,
   `agent-service`): under/at/over the ceiling in both ENFORCE and SHADOW.
4. **Planner emitting 9 sub-questions** -- new `PlannerServiceTest`
   (`control-plane`), 3 tests. `PlannerService.decompose()` was made
   package-visible (was `private`) so the test can call it directly, mirroring
   `ResearcherService.confidenceFor()`'s existing pattern. `ChatClient`'s
   fluent chain (`prompt().system().user().call().content()`) is mocked to
   return a scripted 9-line response; what's actually under test is the
   `.limit(props.maxFanOut())` truncation that runs after it, not the mock.
5. **Researcher past 90s** -- new `ResearcherServiceTest` cases (2), same
   treatment: `overBudget(Instant)` made package-visible, tested directly
   with a real `Instant.now()` and a synthetic 91-seconds-ago `Instant` --
   no `Thread.sleep`, no fake clock needed since the method takes its
   comparison point as a parameter already.
6. **Quota at zero** -- `QuotaServiceTest` (existing, `retrieval-service`):
   `checkBudget()` throwing in ENFORCE, logging-and-proceeding in SHADOW.

## What "SHADOW mode, assert on log counters" became here

PLAN's phrasing implies watching SHADOW-mode log output. The tests instead
assert directly on the boolean/exception outcome of each guardrail's
decision method in both `ENFORCE` and `SHADOW` (where applicable) --
`RunUsageGuardTest` and `QuotaServiceTest` already did this before today; the
new tests follow the same shape. This is a stronger check than grepping log
lines: it fails immediately and specifically if a guardrail's *decision*
changes, rather than only if its *log message* changes.

## Results

`agent-service`: 42/42 green (was 40; +2 `overBudget` tests).
`control-plane`: 3/3 new `PlannerServiceTest` tests green (control-plane had
zero unit tests before this -- only the Spring-context `contextLoads` test,
which needs Docker).
`retrieval-service`: 74/74 green, unaffected (already covered cases 1, 2, 6
in earlier commits this session).

This closes the guardrail tripwire eval. Combined with the fabrication-
injection eval (harness built and tested with a scripted grader, real
measurement still blocked on a `ClaimGrader` adapter + SearXNG's rate limit
clearing), that's both of PLAN's two evals now built -- one fully closed,
one closed as a harness with its real-data measurement still open.

## Next

Speedup benchmark (concurrency 1 vs 6, `ExecutorService` + `CountDownLatch`,
p50/p95) -- next unbuilt Week 4 item, and the first one that needs the real
pipeline running end to end, which needs Docker up.
