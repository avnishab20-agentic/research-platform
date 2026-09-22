# Fabrication-injection eval: first real measurement against DeepSeek

Closed the biggest remaining open item from the last status check: the
fabrication eval had only ever run against a scripted fake grader. Ran it
for real.

## The harness

New `FabricationEvalLiveTest` (agent-service) -- `@SpringBootTest`,
`@Disabled` by default (costs real DeepSeek calls, needs a live Postgres
with real claims, not CI-safe). Pulls known-good `SUPPORTED` claims from a
real verified run already in the dev Postgres (the India renewable-energy
run, 62 claims / 48 SUPPORTED), builds a `ClaimGrader` that calls the real
DeepSeek model through the exact same system prompt and batch-of-one shape
`CriticService.gradeBatch` uses in production, then runs
`FabricationEval.run(...)` against it. `RUN_ID` for the SQL didn't bind at
first -- `c.run_id = ?` with a raw `String` param throws Postgres's
"operator does not exist: uuid = character varying"; fixed with
`UUID.fromString(runId)`.

## The real result

10 corrupted claims, 28 clean claims, run once:

- **Catch rate: 1.0** (10/10 corrupted claims caught) -- passes PLAN's
  >0.85 target outright.
- **False-positive rate: 0.5** (14/28 clean claims wrongly flagged) --
  fails the <0.10 target badly.

## A real methodology gap, found and left honestly reported rather than hidden

The false-positive number is likely inflated by a real difference between
this test and production, not (necessarily) a real critic quality
problem: the test re-grades against the single short quoted sentence
`claim_verdicts.evidence_passage` stored from the *original* grading pass
(179-499 characters, confirmed by direct query), not the full multi-passage
RAG retrieval `CriticService.gradeBatch` actually uses live
(`passageStore.retrieveTopK`, several passages per claim). A single short
sentence often lacks enough surrounding context to fully re-support a
claim on its own, which would legitimately push a model toward `PARTIAL`/
`UNSUPPORTED` on re-grading even for a genuinely correct claim.

**Not "fixed" by re-running until the number looks better.** The fair next
measurement -- re-grading with real `retrieveTopK` passages instead of the
stored one-sentence quote -- is named as the next step, not done here.
Reporting the real, first, un-flattering number (both in this progress log
and in `README.md`'s eval section) matters more than a clean-looking
result that skipped the harder methodology question.

## State after this

- `FabricationEvalLiveTest` restored to `@Disabled` (manual-run only,
  costs real API calls) after getting the one real measurement.
- `README.md`'s eval section updated with the real numbers and the
  evidence-context caveat, replacing the earlier "not yet run" framing.
- Full suite re-verified: `common`+`retrieval-service`+`agent-service`+
  `control-plane`, all green (agent-service's other 43 tests pass with
  `FabricationEvalLiveTest` excluded from the ordinary run).
- Speedup benchmark: explicitly skipped this round per direction --
  deferred rather than re-attempted against SearXNG's uncertain throttle
  state.
