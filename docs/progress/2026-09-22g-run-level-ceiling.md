# 2026-09-22 (part 7) — the run-level ceiling: guardrail #4, fully wired

Continuing through PLAN's guardrail build-order table, one item fully
done before starting the next. This entry: item #4, "run-level ceiling —
search/LLM-call/wall-clock counters, breach → PARTIAL."

## The mechanism

`control-plane/db/migration/V3__run_usage.sql` adds one table:
```sql
CREATE TABLE run_usage (
    run_id     UUID PRIMARY KEY REFERENCES runs(id),
    searches   INT NOT NULL DEFAULT 0,
    llm_calls  INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Same shape and same reasoning as the fan-in counter on `dag_levels`:
`UPDATE ... RETURNING` under Postgres row locking, not an in-memory
counter. `CLAUDE.md` is explicit about why -- an
`AtomicInteger`/`ConcurrentHashMap` counter dies the moment
`agent-service` restarts mid-run, and that breaks restart-survival, a
headline feature of this whole architecture. A Postgres row survives a
restart the same way `dag_levels` already does.

`PlannerService.submit()` inserts the row in the same transaction that
creates the run, before publishing anything to Kafka -- `llm_calls`
starts at `1`, because the planner's own question-decomposition call is
itself one of the run's LLM calls, not a free action outside the budget.

**`agent-service/guardrail/RunUsageGuard.java`** is the one class every
LLM-calling and search-calling site now goes through:
- `trySearch(runId)` / `tryLlmCall(runId)` each run one atomic
  `UPDATE run_usage SET x = x + 1 WHERE run_id = ? RETURNING x`.
- The counter **always increments**, even past the limit -- only the
  *decision* (allow or refuse) depends on the configured max and the
  global `guardrails.mode`. `ENFORCE` refuses once the ceiling is
  crossed; `SHADOW` logs the breach and lets it through anyway. Same
  split QuotaService already uses, applied to a second, independent
  guardrail.
- A missing `run_usage` row (a pre-guardrail run, or a genuine race)
  **fails open** -- logs a warning and allows the call, rather than
  refusing real research work because of a bookkeeping gap that isn't
  the caller's fault.

## Wired into every real LLM/search call site

- `ResearcherService`: `generateQueries` (LLM), `search` (search),
  `synthesizeAnswer` (LLM) -- all three now take the sub-question's
  `runId` and check the guard first. A refusal degrades exactly like an
  existing budget breach already does: an empty result, which
  `research()`'s existing short-circuit logic turns into a `PARTIAL`
  finding. No new failure path was invented -- the ceiling reuses the
  same "budget exceeded" shape the wall-clock/token budget already had.
- `WriterService.extractClaims`: one `tryLlmCall` before the batch claim
  extraction, using `finding.runId()` (already on the record, no new
  parameter needed).
- `CriticService.gradeBatch`: one `tryLlmCall` before the batch grading
  call, alongside the existing "nothing to grade" empty-batch check.

Four real call sites across three classes, same one-line pattern each
time, all reusing the single `RunUsageGuard` bean.

## Config

Both `retrieval-service` and `agent-service` now carry the full
`guardrails:` tree (previously only `retrieval-service` did). Wiring
`GuardrailProperties` (from `common`) into a second service needed the
same `@EnableConfigurationProperties(GuardrailProperties.class)`
registration `retrieval-service` already used -- `common`'s package
isn't inside either service's own `@ConfigurationPropertiesScan` path,
so explicit registration is required every time a new service picks up
the shared tree.

## Tests

**`RunUsageGuardTest`** (5, new): under the ceiling allows; over the
ceiling in `ENFORCE` refuses; over the ceiling in `SHADOW` still allows
(logged); exactly at the ceiling is still allowed (the ceiling is the
last permitted call, not the first refused one); a missing `run_usage`
row fails open rather than refusing.

Existing `ResearcherServiceTest`/`CriticServiceTest`/`FixtureIOTest`
(17) re-verified clean after the constructor signature changes both
classes needed. `retrieval-service`'s full 69-test suite re-verified
unaffected. 23 + 69 = 92 tests green across the two modules touched,
all mocked/file-based, zero Docker needed for any of it.

## What's still not wired to this ceiling

The `run.maxSubquestions` and `run.maxWallClock` fields exist in the
config tree but nothing enforces them yet -- `PlannerService` doesn't
cap how many sub-questions DeepSeek returns against `maxSubquestions`
(it already caps against its own `PlannerProperties.maxFanOut`, a
different, pre-existing limit that happens to default to the same
number but isn't sourced from this tree), and nothing tracks a run's
total wall-clock time against the 10-minute ceiling the way each
individual researcher already tracks its own 90-second budget.
Genuine, named remaining work, not silently treated as done because the
config field exists.

## Next

Move to the next unbuilt item: the fabrication-injection eval (PLAN's
actual Week 4 differentiator -- corrupt known-good claims, re-run the
Critic, measure catch rate). The corruption logic itself (the five
corruption types: SWAP_NUMBER, INVERT, SWAP_ENTITY, FABRICATE, OVERREACH)
is pure data manipulation and can be built and unit-tested without live
services or real fixture data; only the "re-run the Critic" half of the
eval needs the recording session that's still blocked on SearXNG's rate
limit clearing.
