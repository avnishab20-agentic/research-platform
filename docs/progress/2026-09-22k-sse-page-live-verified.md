# 2026-09-22 (part 11) — SSE page live-verified against the real pipeline

Continuing autonomously per the cron-fired instruction. This entry closes
out the SSE page item: it was built and unit-tested in the previous
session (commit `7977e73`), but never actually run against a live pipeline.
It now has.

## A real environment gap found and fixed along the way

Docker was down at the start of this run (the machine had presumably
slept/idled since the earlier session, despite `caffeinate`). Brought it up
with `docker compose up -d`, confirmed all 5 containers healthy, then
started all three Spring apps in the background.

**First live run failed immediately** -- `PlannerService.decompose()` threw
`com.openai.errors.UnauthorizedException: 401` against DeepSeek, and the run
correctly self-reported as `PARTIAL` with zero sub-questions (exactly the
"never silently drop a failed run" behavior CLAUDE.md specifies -- the
guardrail worked as designed even though the underlying cause was an
environment problem, not a code bug).

Root cause: `DEEPSEEK_API_KEY` is exported in the user's interactive
`~/.zshrc`, but this session's non-interactive shell never sourced it, so
every background `mvn spring-boot:run` process inherited an empty value.
`api-key: ${DEEPSEEK_API_KEY}` in both `control-plane` and `agent-service`'s
`application.yml` resolved to a literal empty placeholder, which DeepSeek's
API correctly rejected as invalid. Fixed by `source ~/.zshrc` before
restarting the three services. Not a code change -- environment-only, and
worth naming explicitly since it would silently block every subsequent item
in this backlog (researcher, writer, and critic all call DeepSeek too) if
left undiagnosed.

## The live run

Submitted "What is the current RBI repo rate policy?" via
`POST http://localhost:8083/api/v1/runs`, then watched the real SSE stream
at `GET /api/v1/runs/{id}/events`:

- Planner decomposed into 8 real sub-questions (repo rate, monetary stance,
  MPC meeting date, MPC decision, inflation target, SDF rate, repo-vs-reverse-repo,
  policy objectives) -- genuinely different phrasings each time, not a fixed
  script.
- Watched real per-researcher completion arrive out of order over the
  stream (`completed` climbing 0 -> 2 -> 3 -> 4 ... -> 8, `workers[]`
  flipping each sub-question from `PENDING` to `COMPLETE`/`PARTIAL`
  individually) -- this is the actual Postgres fan-in counter from
  `FanInService`, observed live through the new endpoint, not simulated.
- Run reached `FINDINGS_COMPLETE`, then (once the Writer's Kafka listener
  picked it up) `CLAIMS_READY` (31 claims extracted from 8 findings), then
  (once the Critic finished grading) `VERIFIED`.
- Fetched `GET /api/v1/runs/{id}/report` and inspected the claims directly:
  real source URLs (`rbi.org.in`, `tradingeconomics.com`,
  `bajajhousingfinance.in`), real evidence passages quoted verbatim from
  re-fetched pages ("As per the announcement made by the Reserve Bank of
  India (RBI) on 05 August 2026, the current Repo Rate is 5.25%."), and a
  genuine tier split (tier 1 `rbi.org.in`, tier 3 for the aggregator sites).
  One claim graded `PARTIAL` ("SDF stands for Standing Deposit Facility") --
  its evidence passage is a truncated table header fragment, a legitimate
  partial match rather than a full explanation, which is exactly the kind
  of nuance `PARTIAL` exists to capture.

The whole run -- plan, 8-way parallel research, write, verify -- took
roughly 3.5 minutes end to end, observed continuously through the SSE
stream and the final report endpoint.

## What this confirms about the earlier build

The SSE endpoint's design decisions from the previous session all held up
under a real run:
- Polling `runs.status`/`dag_levels`/`dag_nodes` every second (rather than
  a push from agent-service) correctly surfaced every transition, including
  the ones that happen in a different JVM (`agent-service`) than the one
  serving the stream (`control-plane`) -- Postgres really is the one place
  every writer agrees on.
- The terminal-status check (`VERIFIED`/`UNVERIFIED`/`PARTIAL`) correctly
  ended the stream without needing the client to guess when to stop
  watching.
- CORS from `localhost:8081` worked without any additional configuration
  once the static console's `Verify` tab (also updated in the same commit)
  pointed `fetch`/`EventSource` calls at `controlApi()` on `:8083`.

## Next

Moving to the restart-survival demo -- kill `agent-service` mid-run against
this same live pipeline and confirm the run resumes correctly because
fan-in/`run_usage` state lives in Postgres, not memory. The services are
already up and warm, which is the ideal state to run that demo in.
