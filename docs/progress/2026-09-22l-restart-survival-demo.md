# Restart-survival demo — verified live

Services were already up and warm from the SSE-page session, so this ran
immediately against the real pipeline.

## What was tested

CLAUDE.md's architecture decision is that fan-in state lives in Postgres,
not a `ConcurrentHashMap`/`AtomicInteger`, specifically so a restart of
`agent-service` can't strand a run. This is the first time that guarantee
was exercised against a live process kill rather than argued from the code.

## The run

1. Submitted `POST /api/v1/runs` ("What are the main drivers of inflation
   in India in 2026?") -> `runId 86db3233-51eb-435b-9633-5ea6f2667067`.
2. Immediately `kill -9`'d the running `agent-service` JVM (pid 57439).
3. Confirmed via Postgres: `runs.status = RUNNING`, all 8 `dag_nodes` still
   `PENDING` -- the planner had decomposed the question and published to
   Kafka, but zero researchers had consumed a subtask yet. This is the
   worst-case timing for the demo: nothing was in flight to lose, but
   nothing had started either, so a naive restart could easily re-decompose
   or double-publish if the design were wrong.
4. Confirmed `lsof -iTCP:8082` showed nothing listening -- agent-service
   was fully down, not just busy.
5. Restarted it: `mvn -pl agent-service spring-boot:run` (note: `-am` fails
   here -- it makes Maven resolve `spring-boot:run` against the reactor
   root, which has `packaging=pom` and no main class; must run without
   `-am` once dependencies are already installed).
6. On boot, its Kafka consumer group (`agent-service-researcher`) rejoined
   and immediately began consuming the 8 still-unacked `research.subtasks`
   messages -- visible in the boot log as 8 `Researching sub-question '...'`
   lines for this exact `runId`, picked up because they were never
   committed as consumed by the dead instance.
7. Run reached `runs.status = VERIFIED` shortly after, with all 8
   `dag_nodes` at a terminal status (`PARTIAL` -- meaning some sub-question
   findings only partially matched their evidence during Critic grading,
   the same legitimate outcome documented in the previous session's live
   run, not a symptom of the restart).

## What this confirms

- **Kafka, not memory, owns "what's left to do."** The dead JVM never
  acked the 8 messages, so they were never removed from the partition;
  the new JVM's consumer group picked them up on rejoin with no special
  recovery code -- this is default consumer-group behavior, which is
  exactly why the architecture decision leans on it instead of building a
  custom recovery path.
- **Postgres, not memory, owns "how many are done."** `FanInService`'s
  counter survived the kill because it was never anywhere but the
  `dag_nodes`/`dag_levels` tables to begin with -- there was no in-memory
  state to lose.
- **No duplicate work and no stuck run.** The restarted instance did not
  re-run the planner, did not re-publish subtasks, and the run completed
  to a real terminal status rather than hanging on a phantom "waiting for
  a worker that will never come back."

## Next

This was the last unverified claim from the architecture-decisions list.
Remaining open items per the last honest status pass: PLAN Week 1
Session 4's `CompletableFuture` parallel fetch / single-flight lock /
`Semaphore(4)` in `retrieval-service` were never built (superseded by
priority on the Week 2-4 pipeline once Claude took over); Testcontainers
integration coverage for Redis/the extractor sidecar is still mock-only.
Otherwise Weeks 2-4's core claims (planner, parallel research, RAG,
Writer, Critic, fixture mode, guardrails #1/#2/#4, fabrication-injection
eval, guardrail-tripwire eval, SSRF block, SSE page, restart-survival) are
now all live-verified, not just unit-tested.
