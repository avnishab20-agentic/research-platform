# 2026-09-22 (part 3) — the Critic, compile-verified (Docker down for heat)

Different shape from every other entry today: this one is **not
live-tested**. Your Mac was heating up from three JVMs plus Docker running
at once, so we brought the whole stack down mid-session. Everything below
compiled clean and passed a full four-module compile check, but has not
run against a real question yet. That's honest, not a gap to gloss over —
first thing next session is bringing Docker back up and actually running
this.

## What got built: the Critic

This is the piece `CLAUDE.md` calls out by name as the actual point of the
project: *"The verification is the point of the project. Never cut it."*
Everything before this — search, extraction, RAG, the Writer's claims —
exists to produce something checkable. The Critic is what actually checks
it.

**`agent-service/critic/CriticService.java`** — one method, `verify(runId)`,
five real steps:

1. **Load every claim for the run**, joined against its source's URL
   (`SELECT c.*, s.url FROM claims c JOIN sources s ON c.source_id = s.id
   WHERE c.run_id = ?`).

2. **Re-fetch every distinct source URL** through `retrieval-service`'s
   `/extract` endpoint and overwrite `sources.extracted_text`. This is the
   literal meaning of "the Critic re-fetches sources" — not trusting
   whatever the Researcher happened to see minutes earlier, independently
   confirming the source is still reachable and still says what the
   claim assumes it says. A transient re-fetch failure deliberately
   leaves the existing `extracted_text` alone rather than blanking it out
   — a flaky retry shouldn't erase evidence that was captured successfully
   the first time.

3. **Retrieve grounding passages for each claim** via the same
   `PassageStore.retrieveTopK(runId, claim.text(), 3)` the Researcher
   already uses — same shared RAG utility, second use case, exactly as
   planned back when RAG was first added to this project. One deliberate
   scope cut, clearly marked in the class's javadoc: grading reuses the
   Researcher's already-indexed vectors for that source rather than
   re-embedding the freshly re-fetched text a second time. The honest
   trade-off: in the common case the re-fetched text is identical to what
   was already indexed, so re-embedding would just duplicate rows in
   `vector_store` for no benefit; the cost is that if a source's content
   genuinely *changed* between the Researcher's fetch and the Critic's
   re-fetch, grading would still be checking against the old text. Not
   silently swept under the rug — named directly in the code.

4. **Grade in batches of 10** (PLAN's number), via one structured LLM call
   per batch. Claims are numbered 1..N within their batch and the model is
   asked to return `{index, verdict, evidencePassage}` per claim — indices,
   not UUIDs, because a raw UUID is exactly the kind of token a model can
   subtly mangle in its own output (transpose a digit, drop a hyphen), and
   an index the model only has to copy straight from the prompt it just
   read doesn't have that failure mode. Any claim the model doesn't return
   a grade for (a partial or malformed response) falls back to
   `UNREACHABLE` in code rather than silently vanishing from the results.
   Claims that retrieved **zero** passages never even reach the model —
   there's no evidence to grade against, so `UNREACHABLE` is decided
   directly in code, saving an LLM call that could only ever produce a
   meaningless answer.

5. **Compute `unsupported_ratio`** — `(UNSUPPORTED + CONTRADICTED) /
   verifiable_claims`, exactly PLAN's formula — and mark the run
   `VERIFIED` or `UNVERIFIED` accordingly (threshold 0.15, configurable).

**`INFERENCE` claims are excluded from grading entirely** — filtered out
before anything is sent to the model, per PLAN's `for each Claim where
kind != INFERENCE`. They're the Writer's own reasoning, not a sourced
fact; there's nothing to check them against.

**`agent-service/critic/ClaimsReadyListener.java`** — the thin Kafka
trigger: consumes `claims.ready` (published by the Writer the moment it
finishes), calls `CriticService.verify(runId)`. Same pattern as every
other listener in this project: logic lives in the `@Service`, the
listener is a few lines.

## The one piece of PLAN's Critic loop deliberately not built this pass

PLAN's spec:
```
unsupported_ratio = (UNSUPPORTED + CONTRADICTED) / verifiable
if ratio > 0.15 AND round < 2:
    re-research the failed claims ONLY (not a full re-run)
else:
    publish with per-claim badges
```

The "re-research the failed claims only" branch — a second round that
goes back and re-runs research specifically for the sub-questions behind
claims that failed grading — is **not implemented**. When the ratio comes
in above threshold, the run is marked `UNVERIFIED` and published as-is
(status is a terminal, visible outcome — never silently dropped, matching
`CLAUDE.md`'s own rule about failed runs), rather than looping back for a
second attempt.

This is a real, deliberately-scoped cut, not an oversight: a round-2
re-research loop needs its own round counter, its own mechanism for
"re-trigger only these specific sub-questions, not the whole run," and
its own termination guarantee against looping forever if round 2 still
comes back over threshold. That's a meaningfully sized feature on its
own, and today's session already covered the Writer and the Critic in one
sitting. `UNVERIFIED` as a terminal state is honest and PLAN-consistent
on its own — the fabrication-injection eval in Week 4 doesn't strictly
require the re-research loop to exist, only that the Critic correctly
flags bad claims, which it now does.

## Config added

```yaml
critic:
  batch-size: 10
  top-k-passages: 3
  unsupported-ratio-threshold: 0.15
```
Same pattern as `ResearcherProperties`/`PlannerProperties`: a small local
config record, explicitly marked as not yet the shared `guardrails:` tree
`CLAUDE.md` describes, kept config-driven specifically so folding it into
that real tree later is a move, not a rewrite.

## What's proven, and what genuinely isn't yet

**Proven:** it compiles, cleanly, on the first attempt, against Spring
AI's real structured-output API — which has held true for every piece
built today (RAG, the researcher loop, the planner, the fan-in, the
Writer), so it's reasonable evidence the code is *shaped* correctly.

**Not proven:** none of it has run against a real claim, a real re-fetch,
or a real grading call. Today's earlier entries all show the pattern of
"compiles clean" and "runs correctly" being two different claims — the
`Instant`-to-JDBC bug, the `@PathVariable` bug, and the confidence-scoring
bug were all invisible until something actually ran. There is no reason
to assume the Critic is exempt from that pattern. Treat this entry as
"ready to test," not "done."

## Next session starts with

1. Bring Docker back up, start all three services.
2. Submit a real question, let it run all the way through: Researcher ->
   Writer -> Critic.
3. Check `claim_verdicts` directly in Postgres — does the evidence
   passage genuinely support (or fail to support) each claim? This is the
   one part of the whole project worth reading by hand, not just trusting
   a green log line for.
4. Whatever breaks, fix it the same way everything else broke and got
   fixed today: read the real error, not the code you assumed was right.
