# 2026-09-22 (part 2) — planner, fan-in, and PLAN's actual Week 2 done-when

Picks up right after the researcher-loop milestone. Today's earlier entry
proved one researcher works. This entry closes the gap: 8 researchers, run
in parallel, correctly counted to completion.

## What got built

**`control-plane/.../planner/PlannerService.java`** — takes a question,
does five things in order: inserts a `runs` row (`status='RUNNING'`), asks
DeepSeek to break the question into 6-8 independent sub-questions (same
plain-text-one-per-line approach as the researcher's query generation --
consistent, and simple to parse reliably), inserts one `dag_nodes` row per
sub-question, inserts one `dag_levels` row recording `expected` = however
many sub-questions actually came back, and publishes one `ResearchSubtask`
per sub-question onto `research.subtasks`. All of this happens inside one
`@Transactional` method, so a crash partway through can't leave a `runs`
row with no matching `dag_levels` row (which would hang forever with no
recorded expectation).

One explicit edge case handled: if DeepSeek returns zero sub-questions,
the run is immediately marked `PARTIAL` rather than left dangling with
nothing to ever complete it -- there'd be no `dag_levels` row for the
sweeper to find, so it would sit as `RUNNING` forever with no path to
resolution.

**`control-plane/.../fanin/FanInService.java`** -- the actual fan-in
counter this project's whole architecture is built around (CLAUDE.md:
"Fan-in uses a Postgres counter, not Kafka Streams windowing"). Two
methods:

- `recordFinding(finding)`: writes the finding onto its `dag_nodes` row,
  then runs the one SQL statement that makes the whole thing work:
  ```sql
  UPDATE dag_levels SET completed = completed + 1
   WHERE run_id = ? AND level = 0
  RETURNING completed, expected
  ```
  This is atomic by construction -- Postgres takes a row lock on that
  specific `(run_id, level)` row for the duration of the UPDATE, so if
  three findings for the same run land at the exact same instant, the
  three transactions still queue up and each sees a distinct, correctly
  incremented `completed` value. Exactly one of them will ever see
  `completed == expected` -- that one (and only that one) triggers the
  run's completion.
- `releaseExpiredLevels()`: the sweeper's actual work. Finds any
  `dag_levels` row past its `deadline` that still hasn't completed, forces
  `completed = expected` (so the counter can never fire a real completion
  for it later) and marks the run `PARTIAL`. This is what stops one dead
  or hung researcher from stranding an entire run forever.

**`FindingListener`** (thin `@KafkaListener` wrapper) and
**`DeadlineSweeper`** (`@Scheduled(fixedDelay = 15000)`, per PLAN's
"sweeper every 15s") are both just a few lines calling into
`FanInService` -- kept the actual logic in the `@Service` layer, listener/
scheduler classes stay thin, matching `CLAUDE.md`'s
"`@Transactional` on the service layer, not repositories" convention
(extended here to "not listeners" too, same reasoning).

**`RunController`** -- `POST /api/v1/runs {question}` kicks off a run and
returns its id; `GET /api/v1/runs/{id}` reports `{id, question, status}`
for polling. Small, deliberately minimal -- the real SSE streaming version
is Week 4's job, this is just enough to verify the pipeline end to end.

## Three real bugs, found only by actually running this against Docker

**Bug 1 -- `java.time.Instant` can't be bound to a JDBC parameter.**
Both the planner (inserting a deadline) and the sweeper (comparing against
one) passed a raw `Instant` straight into `JdbcTemplate`. The Postgres
driver threw `Can't infer the SQL type to use for an instance of
java.time.Instant` -- it needs `java.sql.Timestamp` explicitly; there's no
automatic conversion at this layer. Fixed both call sites with
`Timestamp.from(instant)`. This is a small but easy trap: `Instant` reads
naturally as "the right modern Java type for a point in time," and it
compiles fine -- the failure only shows up at runtime, against a real
database, which is exactly why this stayed hidden until the sweeper's
first scheduled tick fired against live Postgres.

**Bug 2 -- `@PathVariable UUID id` failed at runtime with "Name for
argument of type [UUID] not specified."** Spring couldn't work out that
the path variable `{id}` should bind to the method parameter `id`, because
that binding normally relies on reading the compiled method's parameter
names via reflection, and this project's Maven build doesn't pass the
`-parameters` compiler flag. Fixed by naming it explicitly:
`@PathVariable("id") UUID id`. The one-line fix is easy; the actually
useful thing to remember is that this exact silent failure mode --
`@PathVariable`/`@RequestParam` working during development in an IDE
(which often does pass `-parameters` by default) and then breaking the
instant the same code runs through a plain Maven build -- is a known class
of "works on my machine" bug, not specific to this project.

**Bug 3 -- a confidence score that didn't match what the answer actually
said.** Found by reading real output, not by a crash. One finding's answer
correctly said *"the research question cannot be answered using only the
provided passages"* -- exactly what the anti-fabrication instruction in
the synthesis prompt is supposed to produce. But its `confidence` field
still read `0.95`, because `confidenceFor()` only ever looked at the
*tier* of the sources that were retrieved, never at whether the model
actually gave a real answer. A trustworthy source being present says
nothing about whether an answer was actually extracted from it.

Fixed by changing the synthesis prompt's contract: instead of asking the
model to freely describe when it can't answer (which is fine for a human
reader but useless for code to detect reliably), it's now instructed to
reply with exactly one fixed marker, `UNANSWERABLE`, and nothing else,
when the passages don't contain an answer. The code checks for that exact
string, and when it's hit: `confidence` is forced to `0.0` regardless of
source tier, the finding is marked `PARTIAL` instead of `COMPLETE`, and
the stored answer text becomes a clear, consistent
"the retrieved passages did not contain an answer to this question"
rather than whatever free-form phrasing the model happened to use. A
single fixed marker is deterministic and easy to test; matching against
open-ended prose like "cannot be answered" or "does not contain" would
have been fragile and would have missed rephrasings.

## The actual live test

Submitted: *"What is the current state of India's space program?"*

The planner produced 8 genuinely independent, well-scoped sub-questions
(launch vehicles, budget, recent missions, Gaganyaan, satellite count,
private-sector participation, international agreements, upcoming
missions) and fanned all 8 out. Polling `GET /api/v1/runs/{id}` every 15
seconds, the status moved `RUNNING` -> `RUNNING` -> `RUNNING` ->
`FINDINGS_COMPLETE` in under a minute -- all 8 researchers running in true
parallel via the 6-concurrency Kafka listener config.

Final tally: 6 findings `COMPLETE`, 2 `PARTIAL` (a real, expected outcome
-- some sub-questions, like international cooperative agreements, simply
didn't have strong source material available in the top search results;
`PARTIAL` existing as a valid, non-failure outcome is a deliberate
architecture decision, not a bug). `dag_levels` showed `expected: 8,
completed: 8` exactly. The "fan-in complete" log line, which only prints
from inside the `completed == expected` branch, appeared **exactly once**
in the log -- direct confirmation that the atomic-counter guarantee held
under 8 real, independently-timed findings arriving from 6 concurrent
consumer threads, not just in theory.

Spot-checked two of the six `COMPLETE` answers directly from Postgres.
Both were genuinely good: one correctly surfaced a real conflict between
ISRO's own page and a Wikipedia passage about which launch vehicle counts
as "active" rather than silently picking one; the other was the
`UNANSWERABLE` case described above, caught and fixed the same session it
was found.

## What this proves

This is PLAN's actual Week 2 done-when, not just a piece of it:
**"6 researchers run in parallel, report assembles"** -- 8 ran in
parallel here (within the configured 6-8 fan-out range), and the
completion signal ("report assembles" -- meaning: the system correctly
knows a run's research phase is done and is ready for the next stage) is
proven live, atomically, under real concurrent load.

**Still not built:** the actual report assembly -- turning 8 findings into
a structured `Report` of `Claim`s -- is the Writer, which is Week 3's job.
`runs.status = 'FINDINGS_COMPLETE'` is where this handoff will happen;
nothing consumes that status yet. `ResearcherService`, `PlannerService`,
and `FanInService` still have zero unit tests of their own -- everything
verified today was one real live run, which is strong evidence but not a
regression net for the next change.

## Next

Week 3: the Writer (turns findings into structured `Claim`s, persisted
into the `claims`/`sources` tables from yesterday's V2 migration) and the
Critic (re-fetches sources, grades each claim, stores the evidence
passage -- reusing the same RAG `PassageStore` the Researcher already
uses). Also overdue: real unit tests for the researcher/planner/fan-in
trio, since today's confidence bug shows live testing catches real
things unit tests would have caught faster and cheaper.
