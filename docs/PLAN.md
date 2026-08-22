# 4-Week Plan

~20 hrs/week. No buffer — if a week slips, cut UI polish, never verification.

| Wk | Focus | Done when |
|---|---|---|
| 1 | Skeleton + `retrieval-service` | Same query twice: 2nd is a cache hit, 0 credits, <50ms. |
| 2 | Researcher + Kafka fan-out/fan-in | 6 researchers run in parallel, report assembles |
| 3 | Writer + Critic + verification | Injected fabrication is caught and re-researched |
| 4 | SSE page + evals | Clone → `compose up` → verified report in 5 min |

Concurrency and Redis work is woven through weeks 1–2 rather than being a separate
phase — see `LEARNING.md` for why each piece is built by hand instead of using the
framework shortcut.

---

## Week 1 — Skeleton and retrieval

### Session 1: stack comes up
- Maven parent (packaging=pom) + `common`, `retrieval-service`, `agent-service`, `control-plane`
- `docker-compose.yml`: redpanda, postgres (pgvector image), redis, searxng, extractor
- SearXNG `settings.yml` with `json` in `search.formats`
- Flyway baseline: `runs`, `dag_nodes`, `dag_levels`
- Topics: `research.subtasks` (12 partitions), `research.findings` (6), `agent.events` (3)
- **Done:** all containers healthy, all 3 apps green on `/actuator/health`

### Session 2: retrieval-service
Endpoints:
```
POST /api/v1/search    queries[] → results[] + creditsSpent + cacheHits
POST /api/v1/extract   urls[]    → documents[] (text, tier, status)
GET  /api/v1/quota     credits remaining
```

Cache keys (versioned):
```
search:v1:{provider}:{sha256(normQuery|freshness)[0:16]}
extract:v1:{sha256(normUrl)[0:16]}
```

> **Deviation from the original spec (Session 7, 2026-08-22).** The search key was
> originally `sha256(normQuery|maxResults|freshness|minTier)`. We dropped `maxResults`
> and `minTier` because this implementation caches the **raw SearXNG results, before
> tiering and filtering** — `map`/`filter`/`limit` run *after* the cache read. SearXNG
> ignores `maxResults` (confirmed against the live container; capping is client-side)
> and has no concept of tiers (`minTier` is applied by `SourceTierResolver` on our
> side), so neither can change the upstream bytes. Including them would fetch identical
> data twice and burn a second credit. `freshness` stays in the key because it maps to
> SearXNG's `time_range` and *does* change the response — it's kept for correctness even
> though it isn't wired through to SearXNG yet.

URL normalization: lowercase host, strip fragment, strip `utm_*`/`fbclid`/`gclid`/`ref`,
strip trailing slash, **keep** other query params, **sort params alphabetically**.
Query normalization: lowercase, trim, collapse whitespace, **keep quotes**.

TTLs: search 24h, extract 7d. Cap documents at 200KB. Redis `maxmemory-policy allkeys-lru`.

Source tiering v1 = YAML domain allowlist. Tiers 1–2 listed explicitly, default 3,
pattern-match tier 4. No LLM.

### Session 3: extractor sidecar
Python + FastAPI + trafilatura, ~50 lines. `POST /extract {url, html} → {title, text, publishedAt}`.
Returns status: `OK | PAYWALLED | ROBOTS_DENIED | UNREACHABLE | TOO_LARGE`.

> **Deviation from the original spec (Session 10, 2026-08-23).** The sidecar returns only
> `OK | PAYWALLED`. It is handed HTML and never fetches a URL itself, so it cannot observe
> a network failure (`UNREACHABLE`), a `robots.txt` rule (`ROBOTS_DENIED`), or an oversized
> response (`TOO_LARGE`) — those are only visible at fetch time, which happens in Java.
> The full enum is unchanged on `Document.status`; three of its five values are simply set
> by `retrieval-service` rather than by the sidecar. Keeping the sidecar fetch-free also
> keeps the quota boundary intact: `retrieval-service` stays the only thing that touches
> the open web.

### Session 4: Redis and concurrency — write these by hand

Do NOT use `@Cacheable`, a rate-limit library, or a parallel-stream shortcut here.
The whole point is to write the patterns manually once.

**Cache-aside, manually** — `RedisTemplate`, explicit get / miss / compute / set with TTL.
Serialize with Jackson, not JDK serialization.

**Token bucket as a Redis Lua script.** Per-domain, 1 rps, burst 3.
Why Lua and not GET-check-SET: two researchers interleave between the GET and the
SET and both pass the check. Redis executes a Lua script atomically, so the
check and the decrement cannot be split.

**Parallel URL fetching with `CompletableFuture`.** Each researcher fetches 5 URLs;
sequential is 5x slower for no reason. `allOf()` to join, `exceptionally()` per
future so one dead URL doesn't sink the batch. Bound it with the semaphore below.

**Cache stampede protection.** Real problem here: 6 researchers hit the same
uncached URL at once and you fetch one page six times. Single-flight lock via
`SET key val NX PX 30000`; the winner fetches, losers wait ~100ms and re-read cache.
Interview term: "thundering herd".

**`Semaphore(4)` in front of the extractor sidecar.** It will fall over under 12
concurrent extractions. Bounded-resource pattern, ~10 lines.

**Done:** hammer `/api/v1/search` with 20 concurrent identical queries →
exactly one upstream call, 19 cache hits, no rate-limit breach.

### Session 5: CI pipeline — moved to Week 5
Per `CLAUDE.md`, CI/CD is explicitly cut for weeks 1-4. The CI pipeline (build +
test on PR) is now built as part of Week 5's deploy step (see Week 5, step 8),
alongside the `kubectl apply`/`helm upgrade` deploy job it feeds into. Week 1
closes after Session 4.

---

## Week 2 — Parallel research

### The researcher loop (one sub-question)
```
1. generate 3-5 search queries          (Haiku)
2. retrieval-service.search()           → deduplicated URLs
3. filter by tier requirement
4. retrieval-service.extract(top 5)     → clean text (cached)
5. extract candidate passages per source (Haiku)
6. synthesize answer with source refs   (Sonnet)
7. self-assess confidence; downgrade if only tier 3-4 support
8. emit ResearchFinding
```
Hard limits: 90s wall clock, 25k tokens, allocated search budget.
On breach → emit a partial finding with low confidence. **Partial is a valid outcome.**

### Fan-out
Planner (Haiku) emits 6–8 flat sub-questions to `research.subtasks`.
No dependencies this month — everything is level 0.

### Fan-in — write this yourself
```sql
INSERT INTO dag_levels (run_id, level, expected, completed, deadline_at)
VALUES ($1, 0, $2, 0, now() + interval '3 minutes');

UPDATE dag_levels SET completed = completed + 1
 WHERE run_id = $1 AND level = 0
RETURNING completed, expected;
```
Exactly one consumer sees `completed == expected` → triggers the writer.
Sweeper every 15s: any level past `deadline_at` → PARTIAL, released anyway.

### Kafka config — set these before you write the consumer
```yaml
spring.threads.virtual.enabled: true
spring.kafka.listener.concurrency: 6
spring.kafka.consumer.max-poll-records: 1
spring.kafka.consumer.max-poll-interval-ms: 600000
```

### Fixture mode
Spring profile replaying recorded search + LLM responses from JSON.
Build it the day the first researcher works. Zero cost, deterministic tests,
and it's how you develop the SSE page in week 4 without burning credits.

---

## Week 3 — Verification (the differentiator)

### Writer emits structured claims
```java
record Report(String summary, List<Section> sections) {}
record Section(String heading, List<Claim> claims, String narrative) {}
record Claim(String text, UUID sourceId, List<UUID> corroborating, ClaimKind kind) {}
// ClaimKind: FACT | FIGURE | QUOTE | INFERENCE
```
`narrative` = connective tissue only, no facts. INFERENCE claims skip verification
but must be visually distinct in output.

### Critic loop — write this yourself
```
for each Claim where kind != INFERENCE:
  1. load sources.extracted_text (cache hit)
  2. chunk, embed, retrieve top-3 passages by cosine to claim text
  3. grade with Haiku, batched 10 claims per call:
       SUPPORTED | PARTIAL | UNSUPPORTED | CONTRADICTED | UNREACHABLE
  4. persist verdict + the exact evidence passage

unsupported_ratio = (UNSUPPORTED + CONTRADICTED) / verifiable
if ratio > 0.15 AND round < 2:
    re-research the failed claims ONLY (not a full re-run)
else:
    publish with per-claim badges
```

**Cost optimization worth doing:** dedupe near-identical claims by embedding before
verifying. Ten researchers reporting the same figure = verify once, propagate the verdict.

---

## Week 4 — Prove it works

### SSE page (plain HTML, no framework needed)
- Question input
- Live agent progress: which sub-questions are running, done, failed
- Report with per-claim badges; click a badge → see the evidence passage
- The evidence passage is what makes verification credible. Don't skip it.

### Fabrication injection eval
```
1. take a report where all claims graded SUPPORTED
2. corrupt N claims programmatically:
     SWAP_NUMBER   "$4.2B" → "$7.8B"
     INVERT        "grew 12%" → "declined 12%"
     SWAP_ENTITY   attribute to a different company
     FABRICATE     insert plausible claim citing a real source
     OVERREACH     "some analysts" → "analysts unanimously"
3. re-run Critic
4. catch rate     = corrupted flagged not-SUPPORTED / N      target >0.85
   false-pos rate = clean flagged not-SUPPORTED / clean      target <0.10
```
OVERREACH is the hardest and most realistic category. Include it.

### Speedup benchmark
Write the harness by hand — `ExecutorService` with a fixed pool, `CountDownLatch`
so all threads start simultaneously rather than staggered, collect timings, report
p50/p95. This is thread-pool sizing reasoning you'll be asked about directly.

Same 20 questions, fixture mode, concurrency 1 vs 6.
Expect **~2.7x, not 6x** — planner + writer + critic are serial (Amdahl).
Publish the real number with the decomposition. Claiming 6x signals you didn't measure.

### README
Lead with: verification demo, speedup chart, restart-survival demo.
Not the architecture diagram.

---

## Restart-survival demo (do this once, record it)
Start a run → kill the `agent-service` container after 90s → restart it →
run completes correctly from committed offsets + Postgres DAG state.
30 seconds, and no in-process orchestration framework can do it.

---

## Week 5 — Kubernetes + KEDA (explicit addition, not part of the core 4 weeks)

Goal: real pod deployment, KEDA-driven autoscaling on `agent-service`, and a
public URL so friends can use it. ~15-20 hrs of genuinely new work — treat this
as its own week, don't try to absorb it into week 4.

**Prerequisite:** weeks 1-4 complete and working locally first. Don't containerize
onto k8s something that isn't already correct on Compose.

### Build order

1. **VM: bump to 8GB** (~€8-10/mo). k3s control plane + the full stack needs
   more headroom than Compose alone.
2. **Install k3s** on the VM. Keep its built-in Traefik as ingress — don't add
   a second ingress controller.
3. **Install KEDA** via Helm (`helm install keda kedacore/keda`). Not built into
   k3s, a separate add-on.
4. **Postgres, Redpanda, Redis stay as plain Docker on the VM host — NOT in k3s.**
   StatefulSets/PVCs on a single node are pure cost, no benefit at this scale.
   The 3 Java services run as pods and reach these over the VM's local network.
5. **Deployment + Service manifests** for `retrieval-service`, `agent-service`,
   `control-plane`. Secrets (DB password, DeepSeek API key) via `kubectl create
   secret`, referenced by each Deployment. Plain YAML is fine — skip Helm for
   your own services unless it starts feeling repetitive.
6. **KEDA ScaledObject on `agent-service` only** — scales on consumer lag for
   `research.subtasks`. This is the one service where autoscaling is actually
   justified (bursty: idle, then N researchers, then idle). `retrieval-service`
   and `control-plane` get fixed replica counts (2 each) — don't KEDA everything,
   that's cargo culting, not architecture.
7. **Ingress + public URL.** Cheapest path: nip.io gives a free working hostname
   off the VM's IP (`http://<vm-ip>.nip.io`), enough for cert-manager to issue
   real Let's Encrypt HTTPS. A real domain (~₹800/yr) if you want something
   shareable that isn't a raw IP.
8. **Build the CI/CD pipeline (moved from week 1/4) and `deploy.yml`.** None of
   weeks 1-4 had CI/CD — it was explicitly cut until now. Build it in this order:
   - `.github/workflows/ci.yml` — build + test on every PR.
   - Enable the eval gate job in `ci.yml` (moved from week 4's image-publishing
     step): a prompt change that regresses fabrication catch rate should fail
     the build.
   - `.github/workflows/publish.yml` — Jib builds and pushes all three services
     to GHCR on every merge to main, tagged with the git SHA.
   - `deploy.yml`: `kubectl apply` / `helm upgrade`, not SSH+Compose. Register a
     **self-hosted GitHub Actions runner on the VM itself** so the deploy job
     talks to the cluster over localhost — never expose the k8s API (6443) to
     the internet. Firewall stays 22/80/443 only, same as before.

9. **Spring Security + JWT — `control-plane` ONLY.** (~8 hrs)
   `control-plane` is the only service with an ingress, so it's the only one
   that needs auth. Configure it as an OAuth2 resource server validating JWTs.
   `retrieval-service` and `agent-service` stay ClusterIP with **no ingress** —
   unreachable from the internet, so they need network isolation, not auth.
   mTLS between three services on one node is theatre; don't build it.

   The JWT `sub` claim doubles as the key for the per-user daily run cap
   (see Rate limiting below). One mechanism, two purposes.

10. **Rate limiting — 3 layers, 3 different threats.** (~4 hrs)
    - **Traefik `RateLimit` middleware** on the ingress (`average: 10,
      burst: 20` per IP). Stops raw flooding before it reaches any JVM.
      ~10 lines of YAML, zero app code.
    - **Per-domain token bucket** in `retrieval-service` — already built in
      week 1. Protects search quota.
    - **Per-user daily run cap** in `control-plane` — the one that actually
      matters, because the expensive unit is a *run* (~$0.04 of LLM + search),
      not an HTTP request. Same Redis Lua atomicity pattern as week 1:
      ```
      INCR runs:daily:{sub}
      EXPIRE to next midnight on first increment
      if count > 10: reject, "daily limit reached"
      ```

    This is a genuinely good interview answer: "the same rate-limiting
    primitive at three layers for three different threats — request flooding,
    search quota, and LLM cost."

11. **OAuth2 Google login — STRETCH, only if 1-10 went smoothly.** (~8 hrs)
    `spring-boot-starter-oauth2-client` with Google as the provider. Means you
    never handle or store passwords for your friends — Google issues the token,
    `control-plane` validates it. Skip if week 5 is running long; the JWT layer
    above already secures the deployment.

    Do NOT build an authorization server (Spring Authorization Server). Being
    an OAuth2 *client* is an afternoon; being a *provider* is a different
    project entirely.

### Explicitly NOT doing: Eureka / service discovery
Kubernetes has service discovery built in (Services + cluster DNS), and Compose
resolves by service name. Running Eureka on k8s duplicates a platform feature
and is a known anti-pattern. It would also add a 4th deployable to an 8GB VM.

Note that much of Spring Cloud Netflix (Ribbon, Hystrix, Zuul) is in maintenance
mode, superseded by Spring Cloud LoadBalancer, Resilience4j, and Spring Cloud
Gateway. "We used Kubernetes-native service discovery because the platform
provides it" is a stronger answer than having wired up Eureka.

### The demo that proves it (record this)
Push a change → CI runs → merge → `publish.yml` builds and pushes the image →
trigger `deploy.yml` → hit the public URL, see the new version live. Then fire
several research requests at once and run `kubectl get pods -w` while
`agent-service` scales up under load and back down after. That live scaling
moment is the actual payoff — it's what makes "I used KEDA" a real answer in
an interview instead of a buzzword on a resume.
