# 2026-09-22 (part 4) — the full pipeline runs, live: Researcher -> Writer -> Critic -> VERIFIED

This is the entry the whole project has been building toward. Everything
from every earlier entry today (RAG, the researcher loop, the planner,
fan-in, the Writer, the Critic) came together into one real, complete run,
end to end, with a correctly computed verdict at the end.

Two real, serious bugs stood between "compiles clean" and "actually
works," both found only by watching a real run fail to progress and
digging into *why*. Both are now fixed and re-verified.

## Bug 1: all of one run's sub-questions landed on one Kafka partition

**Symptom:** the earlier session's "8 researchers ran in parallel" claim
didn't reproduce today. A fresh run sat at `RUNNING` for the full 3-minute
deadline, then got force-completed by the sweeper as `PARTIAL` -- with
all 8 `dag_nodes` still `PENDING`. Nothing had actually run.

**Root cause:** `PlannerService.submit()` published every
`ResearchSubtask` for a run keyed by `runId.toString()`. Kafka's default
partitioner sends a message to a partition based on a hash of its key --
same key, same partition, every time. So all 8 sub-questions for one run
landed on the *same* one of 12 partitions. Kafka guarantees each partition
is consumed by exactly one consumer within a group, so only one of the 6
listener threads could ever work on this run's sub-questions -- the other
5 sat idle. Eight sub-questions, each taking tens of seconds of real
search+extract+RAG+LLM work, running one at a time on a single thread,
comfortably blew past the 3-minute deadline.

Found by directly inspecting partition offsets
(`rpk topic describe research.subtasks -p`) rather than guessing: one
partition showed `HIGH-WATERMARK: 8`, all eleven others showed `0`.

**Fix:** key the publish by `nodeId` instead -- each sub-question has its
own unique id, so they now spread across all 12 partitions and genuinely
run in parallel across the 6 consumer threads, which was the entire point
of Week 2. One line changed in `PlannerService.java`.

**Why this matters beyond the fix itself:** the original choice to key by
`runId` wasn't arbitrary -- it was made deliberately, for a real reason
("keeps one run's messages co-located, easier to reason about when
debugging"), and that reasoning is still sound for `research.findings`
(control-plane's single fan-in listener, no parallelism to lose there).
But the same choice, applied to a *fan-out* topic where parallelism is the
entire point, was actively harmful. The lesson: a keying decision has to
be checked against what the topic is actually for, not applied as a
blanket habit.

## Bug 2: three Kafka listeners sharing one consumer group caused an endless rebalance loop

**Symptom:** even after fixing the partition key, a fresh run still never
progressed. Zero "Researching sub-question" log lines appeared, despite
messages sitting correctly spread across 4 different partitions.

**Root cause:** `agent-service` now has three separate `@KafkaListener`
methods (`ResearchSubtaskListener` on `research.subtasks`,
`RunReadyListener` on `run.ready`, `ClaimsReadyListener` on
`claims.ready`), and all three were relying on the single global
`spring.kafka.consumer.group-id: agent-service` property. That put 18
consumer threads (6 per listener x 3 listeners) into *one* Kafka consumer
group, but with three genuinely different topic subscriptions among them.

Kafka's default partition assignor (`RangeAssignor`) is built around every
member of a group sharing the *same* subscription. Members with
heterogeneous subscriptions inside one group is a known source of
instability -- the log showed a continuous cycle of
`RebalanceInProgressException` -> `UnknownMemberIdException` ->
`session timed out without receiving a heartbeat response` -> rejoin ->
repeat, for the entire ~9 minutes the group never stabilized. `rpk group
describe agent-service` confirmed it: `STATE: PreparingRebalance`,
persistently, the whole time.

**Fix:** give each listener its own explicit consumer group
(`groupId = "agent-service-researcher"`, `"agent-service-writer"`,
`"agent-service-critic"`) instead of sharing the global default. After the
fix, `rpk group list` showed three separate, `Stable` groups. This is a
one-attribute change per `@KafkaListener` annotation, but the underlying
lesson generalizes: **one Kafka consumer group should correspond to one
logical subscriber with one consistent topic subscription.** The moment a
service adds a second `@KafkaListener` on a different topic, it needs its
own group id -- sharing the default silently works for exactly as long as
there's only one listener, then breaks in a way that produces confusing,
seemingly-unrelated symptoms (nothing consumed, no errors logged at the
application level, only visible in Kafka's own consumer-group internals).

## The actual successful run

Submitted: *"What major renewable energy policies has India announced
recently?"*

Full pipeline, timed from submission:
- `RUNNING` -> `FINDINGS_COMPLETE` in ~60s (8 sub-questions, genuinely
  parallel this time)
- `FINDINGS_COMPLETE` -> `CLAIMS_READY` in ~70s more (Writer extracted 62
  structured claims from the 8 findings)
- `CLAIMS_READY` -> `VERIFIED` in ~90s more (Critic re-fetched sources,
  graded all 62 claims in batches, computed the ratio)

**Verdict distribution:** 48 SUPPORTED, 6 PARTIAL, 5 UNSUPPORTED.
`unsupported_ratio = 5/59 ~= 0.085` (59 = the 62 minus 3 INFERENCE claims
that were correctly excluded from grading), comfortably under the 0.15
threshold -- run correctly marked `VERIFIED`.

Spot-checked several claim/verdict/evidence triples directly from
Postgres, by hand, per the earlier entry's own instruction to actually
read this part rather than trust a green status. All of them held up:
specific, accurate figures (India's 500 GW by 2030 target, having crossed
300 GW as of 31 July 2026, a source-by-source capacity breakdown, the
MNRE's ISTS bid plan spanning FY2023-24 to FY2027-28) each paired with a
real quoted sentence from the actual re-fetched government source page
that justified the SUPPORTED verdict. This is not a simulated or mocked
result -- it is the real system, doing the real thing it was built to do.

## What this closes

PLAN's Weeks 1, 2, and 3 are now all live-verified, not just compiled.
The one explicitly-scoped gap remains PLAN's round-2 "re-research failed
claims" loop (documented in the previous entry) -- everything else in the
Critic's spec is built and proven.

## Session-method note

Per the user's own instruction this session: bring the stack up, test for
real, bring it back down (machine heat management), repeat. This entry is
the product of exactly that cycle -- two real bugs were found and fixed
*because* a real question was pushed all the way through instead of
stopping at "it compiles." Neither bug was visible from reading the code;
both were only visible from watching a real run stall and asking why.

## Next

Bring the stack down (done, this entry was written after `docker compose
down` and confirming no leftover processes). Continue coding Week 4:
the SSE page (wiring the existing `retrieval-service` UI's mock Verify
tab to the now-real pipeline), the fabrication-injection eval, and the
speedup benchmark. The next live-test cycle should also add real unit
tests for `ResearcherService`/`PlannerService`/`FanInService`/
`WriterService`/`CriticService` -- five real classes doing real work,
zero unit tests between them, covered so far only by live runs.
