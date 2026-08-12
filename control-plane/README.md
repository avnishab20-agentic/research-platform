# control-plane

**Port:** 8083

## What this is
The "front door" of the system. It's the only service a browser ever talks to.
It takes in a research question over REST, breaks it into sub-questions
(the "planner"), hands them out to `agent-service` over Kafka (the "fan-out"),
waits for all of them to come back (the "fan-in"), and streams live progress
to the browser over **SSE** (Server-Sent Events — a plain HTTP connection the
server keeps open and writes new updates into, so the page can show "3 of 6
researchers done" without the browser having to keep asking).

## How it will work

**Fan-out:** the planner emits 6–8 sub-questions onto a Kafka topic. Every
research run this month is "flat" — no sub-question depends on another
(see "Why only this" below) — so they can all run in parallel immediately.

**Fan-in — the interesting part:** something has to notice when all 6–8
researchers have finished so the WRITER can run. The naive way is an in-memory
counter (`AtomicInteger`, or a `ConcurrentHashMap<runId, count>`). We're
deliberately **not** doing that:

```sql
UPDATE dag_levels SET completed = completed + 1
 WHERE run_id = $1 AND level = 0
RETURNING completed, expected;
```

Every researcher finishing calls this one `UPDATE ... RETURNING`. Postgres
guarantees only one caller can ever be the one that sees `completed == expected`
right after its own update — so exactly one of them triggers the WRITER, even
if two finish at the exact same millisecond. And because the count lives in
the database, not in this JVM's memory, **restarting `control-plane` mid-run
doesn't lose track of how many researchers have finished.** A background
"sweeper" also runs every 15s to release any run that's been waiting too long,
so one stuck researcher can't strand the whole thing forever.

## Why it's designed this way
"Why not just a `Map` in memory?" is a fair question and the honest answer is:
it works fine right up until you restart the service or run two replicas —
then the count silently resets or splits across instances, and an in-flight
research run just hangs forever with no error. Putting the counter in Postgres
means the atomicity (exactly-once trigger) and the durability (survives a
restart) both come from the database doing what databases are good at,
instead of us reinventing distributed counting badly.

## Why only this — the boundary
This is the **only** service meant to be reachable from outside — the one
that will eventually own auth (JWT validation, planned for Week 5).
`retrieval-service` and `agent-service` are never exposed directly; they only
talk to each other over the internal Docker network. And per this month's
explicit scope cut, sub-questions have **no dependencies on each other** — the
`dag_nodes`/`dag_levels` tables have `depends_on`/`level` columns sitting
unused on purpose, reserved for a possible future where sub-question B needs
sub-question A's answer first. Building that now would be solving a problem
this project doesn't have yet.

## Status
Skeleton only — the Spring Boot app boots and `/actuator/health` responds, but
no REST endpoints, SSE handler, planner, or fan-in logic exist yet. Depends on
`agent-service` producing real findings first, so this comes together in
Week 2 per `docs/PLAN.md`.
