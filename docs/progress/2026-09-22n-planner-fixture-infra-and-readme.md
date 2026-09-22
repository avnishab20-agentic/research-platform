# Planner fixture infrastructure + root README

## Planner fixture record/replay

The speedup benchmark (PLAN Week 4) needs the whole pipeline
deterministic under fixture mode, including the planner's decomposition
step -- discovered mid-session that `control-plane`'s `PlannerService`
had zero fixture infrastructure, unlike `agent-service`. Without it, a
fixture-mode replay would ask the live (non-deterministic) planner to
re-decompose a question, almost never landing on the exact sub-question
phrasing the recorded researcher/writer/critic fixtures were keyed
against -- guaranteed `FixtureMissException` storms on replay.

**First attempt moved `FixtureIO`/`FixtureMissException` into `common`
so both services could share it -- reverted.** `common`'s own pom.xml
carries a documented boundary: it's kept dependency-light on purpose,
with `GuardrailProperties`' plain `spring-boot` annotations as "the one
deliberate exception." `FixtureIO` needs Jackson, slf4j, and
`@Component` (`spring-context`) -- a much bigger footprint than that
exception covers. Silently expanding a documented boundary rather than
respecting it would have been the wrong fix even though it compiled.

**Landed instead:** `PlannerFixtureStore`, a small class local to
`control-plane`'s own `planner` package -- same read/record shape as
`FixtureIO`, using dependencies (`ObjectMapper`, slf4j) `control-plane`
already has via its webmvc starter, so no new dependency and no shared-
module boundary crossed. `PlannerService.decompose()` now branches: the
`fixture` Spring profile replays from `planner-responses.json` keyed on
the question text; `fixtures.record-mode=true` on a live call records
the sub-questions produced. `WriterService`/`CriticService` in
agent-service got the equivalent record-side hook (`recordChat`, same
pattern as `ResearcherService`) -- their fixture *replay* was already
free, since `FixtureChatModel` (a `@Profile("fixture")` bean) swaps out
the underlying `ChatModel` for all of agent-service's chat calls
transparently; only the record-mode write side needed adding. Both
switched from `.entity()` to `.responseEntity()` so recording the raw
response text costs no second LLM call.

All four modules: `common`+`retrieval-service`+`agent-service`+
`control-plane` compile clean and 127/127 tests pass.

## Recording attempt: infra proven, data blocked

Restarted `agent-service`/`control-plane` with `fixtures.record-mode=true`
and ran one real question through the live pipeline. Confirmed the whole
new pipeline works: `planner-responses.json` got its first real entry,
`chat-responses.json` grew (30 -> 38), and the recording mechanism fired
correctly end to end.

**But the search data itself is useless** -- every SearXNG call in that
run returned zero results. Checked directly: all major engines (Google
CSE, Brave, DuckDuckGo, even Wikipedia) are currently rate-limited/
timing out from this environment's heavy automated use earlier in the
session (the 20-concurrent single-flight test, multiple live demo runs).
This is the exact same failure mode documented in session `2026-09-22f`
-- an external, not a code, constraint. Deleted the resulting
zero-result `search-responses.json` rather than commit misleading
fixture data, per that session's own precedent.

Per the user's direction, stopped hammering SearXNG rather than retry
immediately (retrying risks extending the block, not shortening it).
**The speedup benchmark itself is not built and remains a named open
item** -- the infra to build it is now ready; the data capture step is
what's blocked.

## Root README

`docs/PLAN.md`'s Week 4 instruction: "Lead with: verification demo,
speedup chart, restart-survival demo. Not the architecture diagram."
Wrote `README.md` at the repo root (previously only per-module READMEs
existed) using real data already on hand:

- Verification demo -- the RBI repo rate run from session `2026-09-22k`
  (8 sub-questions, 31 claims, real source URLs and evidence passages,
  one legitimate `PARTIAL`, `VERIFIED` in ~3.5 minutes).
- Restart-survival demo -- the process-kill run from session `2026-09-22l`.
- Architecture table, guardrails summary, eval status, run instructions.
- **No speedup number is claimed.** The eval section states plainly that
  the benchmark is blocked and why, quoting PLAN's own warning against
  guessing: "Claiming 6x signals you didn't measure." The
  fabrication-injection eval is similarly reported honestly as
  "unit-tested against a scripted grader, not yet run against the real
  critic with a live DeepSeek model" -- a real distinction, not hedging.

## Not done

- Speedup benchmark: infra ready, data capture blocked on SearXNG.
- Fabrication eval has never been run against the real DeepSeek-backed
  critic -- only the scripted-grader unit tests exist.
- `search-responses.json`/`extract-responses.json` still need a real
  recording run once SearXNG's rate limit clears.
