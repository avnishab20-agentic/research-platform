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

## RAG (Retrieval-Augmented Generation)

### Chunk → embed → retrieve, shared by the Researcher and the Critic
**Built in:** `agent-service`, week 2 (Researcher) and week 3 (Critic)
**Instead of:** an extra LLM call per source to find the relevant passage

> "Walk me through how you'd build RAG over a set of documents."

The three steps, and why each exists:

1. **Chunk** — split each extracted article into paragraph-sized pieces. Too
   large a chunk and the embedding blurs together multiple unrelated ideas
   ("diluted" — the vector ends up an average of several topics, matching
   none of them well); too small and you lose surrounding context a claim
   needs to make sense.
2. **Embed** — turn each chunk into a fixed-length vector (a list of floats)
   such that semantically similar text produces geometrically nearby
   vectors. This project uses a local ONNX model
   (`spring-ai-starter-model-transformers`, all-MiniLM-L6-v2 under the hood)
   instead of a paid embeddings API. That's a deliberate tradeoff, not just
   the lazy option — see the follow-up below.
3. **Retrieve** — embed the query (a sub-question, or a claim's text) the
   same way, then rank stored chunks by cosine similarity and take the
   top-k. `pgvector` does the nearest-neighbor search inside Postgres, so
   there's no separate vector database to run or reason about.

**Two call sites, one utility:** the Researcher retrieves relevant passages
for a sub-question before asking Sonnet to synthesize an answer; the Critic
retrieves the passage closest to a specific claim before grading it
SUPPORTED/PARTIAL/UNSUPPORTED/CONTRADICTED/UNREACHABLE. Same chunk/embed/
retrieve code, two different queries fed into it. That's worth saying
explicitly in an interview — it shows you can see the general pattern
underneath two features that don't look alike on the surface.

**Follow-up you'll get: "Why cosine similarity and not Euclidean distance?"**
Cosine similarity measures the *angle* between two vectors, ignoring their
magnitude — so a short chunk and a long chunk about the same topic still
score as similar, even though the long one's vector has a larger raw
magnitude. Euclidean distance would penalize that length difference as if
it were a difference in meaning.

**Follow-up: "Why not just use OpenAI/Voyage embeddings?"** A paid API
means a new account, a new key, per-call cost, and a network dependency —
none of which is acceptable for this project's Week 4 evals, which have to
run on fixture mode and cost exactly $0. A local model also returns the
*same* vector for the same input every time, which a hosted model doesn't
strictly guarantee across versions — determinism matters when an eval's
pass/fail has to be reproducible. The honest tradeoff to name if asked:
local embeddings are lower quality than a large hosted model, and adding
Voyage/OpenAI later is a one-line Spring AI config swap, not a rewrite —
the `VectorStore`/`EmbeddingModel` interfaces are what make that painless.

**Follow-up: "How do you keep one run's documents from polluting another
run's search results?"** Every stored chunk is tagged with the `runId` it
came from, and retrieval always filters on it. Without that, sub-question 3
of run A could retrieve a passage fetched for run B — wrong answer, and a
subtle one, since nothing throws an error.

### RAG vs fine-tuning vs long-context stuffing
**Not built, but you'll be asked**

> "Why RAG instead of just fine-tuning the model on your documents, or
> pasting everything into a long-context window?"

Fine-tuning bakes facts into model *weights* — expensive to update (retrain
for every new article), and the model can still hallucinate a fact that
sounds like something it was trained on. RAG keeps facts in an external,
inspectable store and retrieves fresh at query time — an article changes,
you re-embed it, no retraining. Long-context stuffing (pasting all 30
articles into one huge prompt) burns tokens on mostly-irrelevant text and
still buries the right sentence in noise; retrieval is what narrows 30
articles down to the 3 paragraphs that actually matter for *this*
sub-question.

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
**Built in:** `.github/workflows/` (details: [DEPLOYMENT.md](DEPLOYMENT.md))

> "Walk me through your CI/CD."

Pull request → `backend-pr-validation` (build, all tests, coverage, Docker
builds) plus the SonarQube Quality Gate, both required before merge. Merge to
`main` → per-service pipeline: **test → build → deploy → verify**. Each stage
only runs if the one before passed. Verify waits for the rollout and calls the
health URL, because "the deploy command succeeded" isn't the same as "the app
works". All six services share one recipe (`_build-deploy.yml`), so a pipeline
change is made once, not six times.

### Immutable tags
> "Why not `latest`?"

Rollback is impossible against a moving pointer, and "what's running right now"
becomes unanswerable. SHA tags make rollback a one-line change and an audit trail
free.

### Least privilege
> "What permissions does your pipeline have?"

Each job asks only for what it needs (`contents: read`, plus `id-token: write`
for jobs that log in to Azure). There is **no stored Azure password**: GitHub
hands Azure a short-lived OIDC token, and Azure checks it against a federated
credential tied to this repo's `main` branch. Third-party actions are pinned
to full commit SHAs, so a moved tag can't inject code into a job with cloud
access.

### Build caching
`actions/setup-java` with `cache: maven` reuses downloaded dependencies between
runs. Each Dockerfile copies the `pom.xml` files before the source code, so the
dependency layer is rebuilt only when a pom changes.

### Migrations and rollback
> "What happens to your DB schema when you roll back?"

Flyway runs on startup and takes a lock, so concurrent starts are safe. The real
rule: migrations must be backward-compatible with the *previous* image, or a
rollback breaks against the new schema. Additive changes; never drop a column in
the same release that stops using it.

---

## Security

### Where auth lives, and where it doesn't
**Status: planned, not built yet.** Today there are no user accounts: the
public site is protected only by the daily run cap and by exposing a single
front door (Caddy). The notes below are the plan and the answer to give once
it's built.

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
**Status: planned, not built yet.** Today `agent-service` runs a fixed single
copy, and only `retrieval-service` has an autoscaler (CPU-based, 1–2 copies).

`agent-service` is genuinely bursty — idle, then N researchers, then idle — and
scales on Kafka consumer lag. `retrieval-service` and `control-plane` get fixed
replicas because their load is steady. Autoscaling everything is cargo culting;
being able to say *why* one service and not three is the point.
