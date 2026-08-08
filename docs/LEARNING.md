# LEARNING.md

What each hand-built piece of this project teaches, and the interview question it
answers. Keep this open when you're prepping — the goal is to answer from
*something you shipped* rather than something you read.

---

## Redis

### Cache-aside, written manually
**Built in:** `retrieval-service`, week 1
**Instead of:** `@Cacheable`

> "How would you add caching to a read-heavy endpoint?"

Get → miss → compute → set with TTL. Know why the TTL exists, why you serialize
with Jackson rather than JDK serialization (portable, human-readable in
`redis-cli`, no `serialVersionUID` landmines), and what happens when Redis is
down — cache-aside degrades to slow-but-correct, which is the right failure mode.

**Follow-up you'll get:** "Cache-aside vs write-through vs write-behind?"
Yours is cache-aside because the source of truth is an external API you don't
control, so there's no write path to intercept.

### Token bucket as a Lua script
**Built in:** `retrieval-service` rate limiter, week 1

> "How do you make a check-then-act operation atomic in Redis?"

This is the strongest answer in your Redis toolkit. GET the count, check it, SET
the new value — two requests interleave between the GET and the SET and both
pass. Redis runs a Lua script atomically as a single unit, so the read and the
write can't be split.

Also covers: why Redis being single-threaded makes this work, and why `INCR` is
atomic but `GET`+`SET` isn't.

### Single-flight / stampede protection
**Built in:** `retrieval-service`, week 1

> "Six requests for the same uncached key arrive simultaneously. What happens?"

Naive cache-aside fetches six times. `SET key val NX PX 30000` — one winner
fetches, losers wait ~100ms and re-read the cache. The `NX` is the lock, the `PX`
is the safety valve so a crashed winner doesn't deadlock everyone forever.

**The term is "thundering herd" or "cache stampede."** Use it.

**Follow-up:** "What if the winner dies?" — the PX expiry releases it, and a
loser becomes the next winner. That's why you never use `SETNX` without a TTL.

### Redis vs Kafka in the same system
**Visible in:** the whole architecture

> "You used both — why?"

Redis holds ephemeral shared state: cache, rate-limit counters, locks. Kafka
carries durable work that must survive a restart. Losing Redis costs you
performance; losing Kafka costs you research runs.

---

## Concurrency

### `CompletableFuture` for parallel fetching
**Built in:** researcher agent, week 2

> "How do you run five independent IO calls in parallel in Java?"

The most-asked Java concurrency topic after thread pools. `supplyAsync` per URL,
`allOf().join()` to wait, `exceptionally()` **per future** so one dead URL
returns a placeholder instead of failing the batch.

**Follow-up:** "Which executor does it use by default?" — `ForkJoinPool.commonPool`,
which is sized for CPU-bound work and is wrong for blocking IO. You pass an
explicit executor. With virtual threads enabled,
`Executors.newVirtualThreadPerTaskExecutor()` is the right one here.

### Bounded concurrency with `Semaphore`
**Built in:** extractor sidecar guard, week 1

> "How do you stop a downstream service from being overwhelmed?"

`Semaphore(4)`, `acquire()` / `release()` in a finally block. The finally is the
whole question — a leaked permit permanently shrinks your pool and the symptom
appears hours later as unexplained slowness.

**Follow-up:** "Semaphore vs a fixed thread pool?" — the pool bounds threads, the
semaphore bounds concurrent *access to a resource*. With virtual threads you have
effectively unlimited threads, so the semaphore is doing the real work.

### Virtual threads
**Built in:** `agent-service`, week 2

> "When do virtual threads help and when don't they?"

They help when you're blocked on IO, which is ~95% of this project's wall clock.
They don't help CPU-bound work at all. Know that `synchronized` blocks used to
pin a virtual thread to its carrier (use `ReentrantLock` instead) — a favourite
follow-up.

### Distributed vs in-process coordination
**Built in:** the fan-in decision, week 2

> "Why not a ConcurrentHashMap for the completion counter?"

**This is your best system-design answer in the whole project.** A
`ConcurrentHashMap` or `AtomicInteger` is correct within one JVM and wrong the
moment you have two replicas or a restart — state vanishes and in-flight runs
strand forever. The counter is in Postgres with `UPDATE ... RETURNING`, so the
atomicity comes from the database and survives both.

Ties directly to: "how do you coordinate across instances?" Answer: you don't use
in-memory primitives. You use a shared store with atomic operations.

### Race conditions you actually hit
**Built in:** fan-in, week 2

> "Give me a real race condition you've debugged."

Two researchers finish simultaneously. Both read `completed = 5`, both write 6,
both see `6 != 6`... or both see equality and dispatch the writer twice. The fix
is `UPDATE ... SET completed = completed + 1 ... RETURNING` — read and write in
one atomic statement, so exactly one caller observes equality.

Having a concrete war story here is worth far more than reciting definitions.

### Thread pool sizing
**Built in:** benchmark harness, week 4

> "How do you size a thread pool?"

CPU-bound ≈ cores. IO-bound ≈ much higher, or virtual threads. Your benchmark
sweeps concurrency 1→12 and you have a real curve showing where it flattens.

**`CountDownLatch`** in the harness: all threads start simultaneously instead of
staggering, so you measure contention rather than ramp-up. That's a good detail
to mention unprompted.

---

## Theory the project does NOT teach

Read these separately — building this won't cover them:

- Java memory model, happens-before, `volatile` semantics
- `ConcurrentHashMap` internals (segments, CAS, resize)
- Deadlock: the four conditions, lock ordering, detection
- `ReentrantLock` vs `synchronized`, fairness, `tryLock`
- `ThreadLocal` leaks in pooled threads
- Redis persistence: RDB vs AOF, eviction policies beyond LRU

The project gives you **stories**, not coverage. Interviews want both.

---

## DevOps

### Pipeline design
**Built in:** `.github/workflows/`, weeks 1 and 4

> "Walk me through your CI/CD."

Trigger → build/test → publish images → manual deploy. Know why each stage is
separate: tests gate merges, images are built once and promoted (never rebuilt
per environment), deploys are deliberate.

### Immutable tags
> "Why not `latest`?"

Rollback is impossible against a moving pointer, and "what's running right now"
becomes unanswerable. SHA tags make rollback a one-line change and an audit trail
free.

### Least privilege
> "What permissions does your pipeline have?"

`contents: read` by default; `packages: write` only on the publish workflow. Not
a blanket token. Deploy uses a dedicated SSH key, not your personal one.

### Build caching
`actions/setup-java` with `cache: maven` keyed on pom hashes. Jib over buildpacks
because layer caching separates dependencies from application classes — your
deps layer almost never changes, so pushes are seconds.

### Migrations and rollback
> "What happens to your DB schema when you roll back?"

Flyway runs on startup and takes a lock, so concurrent starts are safe. The real
rule: migrations must be backward-compatible with the *previous* image, or a
rollback breaks against the new schema. Additive changes; never drop a column in
the same release that stops using it.
