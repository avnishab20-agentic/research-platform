# 4-Week Plan

~20 hrs/week. No buffer — if a week slips, cut UI polish, never verification.

| Wk | Focus | Done when |
|---|---|---|
| 1 | Skeleton + `retrieval-service` + CI | Same query twice: 2nd is a cache hit, 0 credits, <50ms. CI green on PR. |
| 2 | Researcher + Kafka fan-out/fan-in | 6 researchers run in parallel, report assembles |
| 3 | Writer + Critic + verification | Injected fabrication is caught and re-researched |
| 4 | SSE page + evals + image publishing | Clone → `compose up` → verified report in 5 min |

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
search:v1:{provider}:{sha256(normQuery|maxResults|freshness|minTier)[0:16]}
extract:v1:{sha256(normUrl)[0:16]}
```

URL normalization: lowercase host, strip fragment, strip `utm_*`/`fbclid`/`gclid`/`ref`,
strip trailing slash, **keep** other query params, **sort params alphabetically**.
Query normalization: lowercase, trim, collapse whitespace, **keep quotes**.

TTLs: search 24h, extract 7d. Cap documents at 200KB. Redis `maxmemory-policy allkeys-lru`.

Source tiering v1 = YAML domain allowlist. Tiers 1–2 listed explicitly, default 3,
pattern-match tier 4. No LLM.

### Session 3: extractor sidecar
Python + FastAPI + trafilatura, ~50 lines. `POST /extract {url, html} → {title, text, publishedAt}`.
Returns status: `OK | PAYWALLED | ROBOTS_DENIED | UNREACHABLE | TOO_LARGE`.

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

### Session 5: CI pipeline
See `.github/workflows/ci.yml`. Green on PR before week 1 closes.

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

### Image publishing
Turn on `.github/workflows/publish.yml` — Jib builds and pushes all three services
to GHCR on every merge to main, tagged with the git SHA.
`deploy.yml` stays manual (`workflow_dispatch`) until you actually have a VM.

Also enable the eval gate job in `ci.yml` once the fabrication harness exists:
a prompt change that regresses catch rate should fail the build.

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
