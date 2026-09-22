# Research Platform

A multi-agent research system: a question is decomposed into sub-questions,
researched in parallel over Kafka, synthesized into a report — then
**verified claim-by-claim against the sources it cites**. Verification is
the point of the project, not a bolt-on.

## Verification demo

A real run, live against DeepSeek, SearXNG, and Postgres — not a script.

**Question:** *"What is the current RBI repo rate policy?"*

1. The planner decomposed it into 8 independent sub-questions (repo rate,
   monetary stance, MPC meeting date, MPC decision, inflation target, SDF
   rate, repo-vs-reverse-repo, policy objectives) — genuinely different
   phrasings each time, not a fixed script.
2. All 8 ran in parallel as Kafka consumers, fanning out and back in through
   a Postgres counter (`dag_levels`), not an in-memory one — see
   [restart-survival](#restart-survival-demo) for why that matters.
3. The writer extracted 31 structured claims from the 8 findings — each
   claim tagged with exactly one source, never free prose with footnotes.
4. The critic re-fetched every cited source independently and graded each
   claim against the re-fetched text: real source URLs (`rbi.org.in`,
   `tradingeconomics.com`, `bajajhousingfinance.in`), real evidence passages
   quoted verbatim ("As per the announcement made by the Reserve Bank of
   India (RBI) on 05 August 2026, the current Repo Rate is 5.25%."), and a
   genuine tier split (tier 1 `rbi.org.in`, tier 3 for the aggregator
   sites).
5. One claim graded `PARTIAL` — its evidence passage turned out to be a
   truncated table-header fragment, a legitimate partial match rather than
   a full explanation. That's exactly the nuance `PARTIAL` exists to
   capture, not a bug.
6. The run reached `VERIFIED` end to end — plan, 8-way parallel research,
   write, verify — in roughly 3.5 minutes, watched live through the SSE
   stream (`GET /api/v1/runs/{id}/events`) and the final report endpoint.

Every claim in the output is checkable: click through to its source, read
the exact passage the critic matched, and see why it was graded what it
was. That's the whole point — a report you can audit, not one you have to
trust.

## Restart-survival demo

The project's central architecture bet: **fan-in state lives in Postgres,
not a `ConcurrentHashMap` or `AtomicInteger`**, specifically so a crashed
`agent-service` can't strand a run. Verified against a real process kill,
not argued from the code:

1. Submitted a run ("What are the main drivers of inflation in India in
   2026?"). The planner decomposed it into 8 sub-questions and published
   them to Kafka.
2. `kill -9`'d the running `agent-service` JVM immediately — before any
   researcher had consumed a single subtask. Confirmed via Postgres:
   `runs.status = RUNNING`, all 8 `dag_nodes` still `PENDING`, and
   `lsof -iTCP:8082` showing nothing listening.
3. Restarted `agent-service`. On boot, its Kafka consumer group rejoined
   and immediately began consuming the 8 still-unacked messages — visible
   in the boot log as 8 fresh `Researching sub-question '...'` lines for
   the exact same `runId`.
4. The run reached `VERIFIED` shortly after, with no re-decomposition, no
   duplicate publishing, and no stuck state.

**What made this work, with no custom recovery code:** the dead JVM never
acked the 8 Kafka messages, so they were never removed from the partition —
the new JVM's consumer group picked them up on rejoin via Kafka's own
default rebalancing. `FanInService`'s completion counter survived the kill
because it was never anywhere but `dag_nodes`/`dag_levels` to begin with —
there was no in-memory state to lose.

## Architecture

| Service | Port | Responsibility |
|---|---|---|
| `retrieval-service` | 8081 | Search, fetch, extract, cache, rate limit. The quota boundary — no other service calls a search API directly. |
| `agent-service` | 8082 | RESEARCHER / WRITER / CRITIC as Spring profiles, not separate deployables. Kafka consumers. |
| `control-plane` | 8083 | REST + SSE + orchestration: planner, fan-out, fan-in, budget. |

Plus `common/` (shared records, no main class) and two sidecars:
`extractor` (Python/trafilatura, HTML → clean text) and `searxng`
(self-hosted search, the only search provider — no paid API key, no
per-query cost).

**Kafka for long-running/durable hops** (orchestrator ↔ researchers).
**HTTP for cache lookups** (agents → retrieval-service). Not everything
goes on Kafka.

## Guardrails

Every limit is a number in one `guardrails:` tree, never a hardcoded
constant or a scattered `@Value`. One global switch:

```yaml
guardrails:
  mode: ENFORCE   # ENFORCE | SHADOW — SHADOW logs a breach and proceeds anyway
```

Covers: run-level ceilings (wall clock, search count, LLM-call count,
sub-question fan-out, critic rounds), researcher-level budgets (wall clock,
tokens, searches), and retrieval hardening (per-domain rate limit,
extractor concurrency, document size cap, scheme allowlist, private-network
SSRF block). **A failed run is always published** — `PARTIAL` or an
`UNVERIFIED` banner, never silently dropped, because suppressing a bad
report would hide the exact behavior this project exists to expose.

Guardrail decision logic is covered by direct, targeted tests in each
service that owns it (a single cross-module eval harness isn't feasible —
the three services don't share a test classpath — so each of PLAN's six
tripwire cases has its own test living next to the code it tests).

## Evals

Three, all designed to run on **fixture mode** — recorded search/LLM
responses replayed from JSON, deterministic, $0 per run.

1. **Fabrication injection** — corrupts claims programmatically (swap a
   number, invert a polarity word, swap the named entity, insert a
   fabricated-but-plausible claim, or overreach a hedge into an absolute),
   re-runs the critic, and measures catch rate vs. false-positive rate.
   Both directions matter: a critic that flags everything scores a perfect
   catch rate and is useless. **Run once against the real DeepSeek-backed
   critic** (10 corrupted / 28 clean claims from a real verified run):
   catch rate **1.0**, false-positive rate **0.5** — passes the catch-rate
   target (>0.85) but fails false-positives (<0.10). Likely partly an
   artifact of the test's evidence input: it re-grades against the single
   short quoted sentence `claim_verdicts` stored from the original pass,
   not the full multi-passage RAG retrieval the live critic actually uses
   — a real difference, not yet controlled for. Reported honestly rather
   than re-run until it passes; the fairer next measurement (full
   `retrieveTopK` passages) is not yet done.
2. **Guardrail tripwire** — one test per guardrail (oversized document,
   private-network URL, over-budget run, over-cap planner fan-out,
   over-budget researcher, exhausted quota), asserting the decision logic
   fires. All six covered.
3. **Speedup benchmark** — concurrency 1 vs. 6 researchers, same question
   set, fixture mode. **Status: blocked.** Full fixture-mode replay needs
   the whole pipeline deterministic, including the planner's decomposition
   step — recording that meant giving `control-plane` its own fixture
   record/replay store (`PlannerFixtureStore`), which is now built and
   verified working. The actual data capture is blocked on SearXNG's
   real-world rate limiting under repeated automated use; this project
   would rather report an honest "not yet measured" than a guessed number
   — PLAN's own words: *"Expect ~2.7x, not 6x — planner + writer + critic
   are serial. Claiming 6x signals you didn't measure."*

## Running it

```bash
docker compose up -d
mvn -pl retrieval-service spring-boot:run &
mvn -pl agent-service spring-boot:run &
mvn -pl control-plane spring-boot:run &
```

Requires `DEEPSEEK_API_KEY` in the environment (DeepSeek V4.1 Flash, via
Spring AI's OpenAI-compatible client — swapped from Claude, cost-driven).
Embeddings are local and free (`spring-ai-starter-model-transformers`, an
ONNX model, no API key, no cost) regardless of chat provider.

```bash
curl -X POST http://localhost:8083/api/v1/runs \
  -H "Content-Type: application/json" \
  -d '{"question":"What is the current RBI repo rate policy?"}'
```

Watch it live at `GET /api/v1/runs/{id}/events` (SSE), or open the plain
console at `http://localhost:8081` — Overview, Retrieve, Extract, and
Verify tabs, all wired to the real endpoints.

## Explicitly cut (this phase)

DAG dependencies (flat fan-out only), reconciler/contradiction detection,
multi-provider search routing, CI/CD, a rich UI, auth/multi-user/billing.
Kubernetes + KEDA autoscaling, JWT auth, and 3-layer rate limiting are a
separate, deliberate follow-on phase — not forgotten, not in scope yet.
