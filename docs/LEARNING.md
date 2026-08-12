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

---

## Security

### Where auth lives, and where it doesn't
**Built in:** `control-plane`, week 5

> "How did you secure communication between your microservices?"

The honest and correct answer: **I didn't expose them.** `control-plane` is the
only service with an ingress and it validates JWTs as an OAuth2 resource server.
`retrieval-service` and `agent-service` are ClusterIP-only — unreachable from
outside the cluster. They need network isolation, not authentication.

mTLS between three services on a single node is theatre. Knowing when *not* to
add a security layer is the more senior answer.

**Follow-up:** "What if an attacker gets inside the cluster?" — then you've lost
already at this scale; defense would be network policies, and that's worth doing
at a size where the blast radius justifies it.

### JWT as more than auth
The `sub` claim keys the per-user daily run cap. One credential, two jobs:
identity and quota attribution. Worth mentioning unprompted — it shows you
thought about cost, not just access.

### OAuth2 client vs authorization server
> "You said you used OAuth2 — which part?"

Client. Google issues the token, we validate it, we never store a password.
Building an authorization *server* (Spring Authorization Server) is an entirely
different scale of project. Know which side of that line you're on — candidates
routinely say "I implemented OAuth2" meaning they added a login button.

---

## Architecture decisions you'll be challenged on

### Shared `common` module vs schema-first
> "A shared library across microservices — isn't that coupling?"

Yes, deliberately. Spring AI binds LLM output directly to these records, so a
mismatch between what the writer emits and what the critic expects becomes a
**compile error** instead of a 2am runtime failure.

The purist alternative — each service owns its types, contract lives on the wire
as JSON Schema or Protobuf — is correct once independent teams ship on
independent schedules and a shared jar would force lockstep releases. One team,
one release cadence: the coupling costs nothing and buys compiler-checked
contracts.

**The answer that lands: "right call for one team's release cadence, wrong call
the moment there are two."** Knowing the trade-off beats knowing the rule.

### Monorepo vs polyrepo
Polyrepo solves *team* decoupling. One person writing both ends of every
contract has no team to decouple from — it would just mean four clones, four CI
setups, and version-bumping a shared jar by hand.

### Why no Eureka
Kubernetes provides service discovery natively (Services + cluster DNS); Compose
resolves by service name. Eureka on k8s duplicates a platform feature and is a
recognized anti-pattern. Also relevant: Spring Cloud Netflix's Ribbon, Hystrix
and Zuul are in maintenance mode, superseded by Spring Cloud LoadBalancer,
Resilience4j and Spring Cloud Gateway.

### Why KEDA on only one service
`agent-service` is genuinely bursty — idle, then N researchers, then idle — and
scales on Kafka consumer lag. `retrieval-service` and `control-plane` get fixed
replicas because their load is steady. Autoscaling everything is cargo culting;
being able to say *why* one service and not three is the point.
