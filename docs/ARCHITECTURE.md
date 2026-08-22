# Research Platform — Architecture

> Status date: 2026-08-22 · Week 1 (skeleton + retrieval). This document shows the
> **whole target system**, not just what exists today, and is honest about what is
> **built** vs still **planned**. All diagrams are plain ASCII on purpose — they
> render in any viewer (IntelliJ preview, terminal, GitHub) with no plugins.

---

## The architecture, one picture

A robot-researcher platform: a question is split into sub-questions, researched in
parallel, synthesized into a report, then **verified claim-by-claim against the sources
it cites** — the verification is the differentiator, never cut.

`retrieval-service` is the *librarian*: the one chokepoint every agent must go through
to touch the web. It owns search credits, caching, and source trust.

**Legend:** `(B)` = built and tested today · `(P)` = planned, not yet built.
Circled numbers `①`–`⑩` are the run lifecycle steps explained step-by-step below.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ BROWSER — plain HTML page  (P)                                             │
├────────────────────────────────────────────────────────────────────────────┤
│  asks a question, watches live agent progress,                             │
│  clicks a per-claim badge to reveal its evidence passage                   │
└────────────────────────────────────────────────────────────────────────────┘
        │  ① POST /api/v1/runs         ▲  ⑩ SSE: /runs/{id}/events            
        ▼                              │  (progress + per-claim badges)       
┌────────────────────────────────────────────────────────────────────────────┐
│ CONTROL-PLANE  :8083 — orchestration  (P)                                  │
├────────────────────────────────────────────────────────────────────────────┤
│  Planner (Haiku): 1 question -> 6-8 flat sub-questions                     │
│  Fan-out: publish subtasks, seed dag_levels (level 0,                      │
│           expected=N, deadline = now()+3min)                               │
│  Fan-in:  UPDATE dag_levels SET completed=completed+1                      │
│           ... RETURNING -- exactly ONE consumer sees                       │
│           completed==expected -> triggers the WRITER                       │
│  Sweeper: every 15s, level past deadline -> PARTIAL                        │
│  SSE hub: consumes agent.events, streams to browser                        │
│  owns: runs / dag_nodes / dag_levels  -> POSTGRES                          │
└────────────────────────────────────────────────────────────────────────────┘
        │  ② publish subtasks          ▲  ⑥ consume findings                  
        ▼                              │  (last one fires the writer)         
┌────────────────────────────────────────────────────────────────────────────┐
│ REDPANDA  :9092 — Kafka API  (B)                                           │
├────────────────────────────────────────────────────────────────────────────┤
│  research.subtasks (12 partitions)                                         │
│  research.findings (6 partitions)                                          │
│  agent.events      (3 partitions)                                          │
└────────────────────────────────────────────────────────────────────────────┘
        │  ③ consume, x6 in parallel   ▲  ⑤ publish ResearchFinding           
        ▼                              │                                      
┌────────────────────────────────────────────────────────────────────────────┐
│ AGENT-SERVICE  :8082 — one deployable, three Spring profiles  (P)          │
├────────────────────────────────────────────────────────────────────────────┤
│  RESEARCHER x6                                                             │
│    1 generate 3-5 queries (Haiku)                                          │
│    2 retrieval-service.search()   -> deduplicated URLs                     │
│    3 filter by tier requirement                                            │
│    4 retrieval-service.extract(top 5) -> clean text (cached)               │
│    5 extract candidate passages per source (Haiku)                         │
│    6 synthesize answer with source refs (Sonnet)                           │
│    7 self-assess confidence; downgrade if tier 3-4 only                    │
│    8 emit ResearchFinding  (partial is a VALID outcome)                    │
│                                                                            │
│  WRITER (Sonnet profile)                                                   │
│    findings -> Sections of Claims, exactly ONE sourceId each               │
│    narrative field = connective tissue only, no facts                      │
│                                                                            │
│  CRITIC (Haiku profile)   -- see steps 7-9 below                           │
│    batches of 10 claims: SUPPORTED / PARTIAL / UNSUPPORTED /               │
│    CONTRADICTED / UNREACHABLE -- verdict + EXACT evidence                  │
│    passage persisted                                                       │
└────────────────────────────────────────────────────────────────────────────┘
        │  ④ HTTP: every web touch goes through retrieval-service             
        ▼     (search AND the Critic's re-fetch of cited sources)             
┌────────────────────────────────────────────────────────────────────────────┐
│ RETRIEVAL-SERVICE  :8081 — the quota boundary  (B/P mixed)                 │
├────────────────────────────────────────────────────────────────────────────┤
│  POST /search   (B) Redis cache-aside first; miss -> SearXNG               │
│                 (1 credit), tier-filter, then cap maxResults               │
│  POST /extract  (P) Semaphore(4) -> extractor sidecar,                     │
│                 docs cached 7d, 200KB cap per doc                          │
│  GET  /quota    (B) daily credits remaining, Redis counter                 │
└────────────────────────────────────────────────────────────────────────────┘
        │  cache hit                    │  cache miss -> real upstream call   
        ▼                                ▼                                    
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│ REDIS :6379          │  │ SEARXNG :8080        │  │ EXTRACTOR :8000      │
│ (B)                  │  │ (B)                  │  │ (P)                  │
├──────────────────────┤  ├──────────────────────┤  ├──────────────────────┤
│ search:v1 TTL 24h    │  │ metasearch engine    │  │ FastAPI +            │
│ extract:v1 TTL 7d    │  │ the ONLY door out    │  │ trafilatura          │
│ quota:v1 counter     │  │ to the live web      │  │ clean article        │
│ token bucket (P)     │  │ format=json          │  │ text + status        │
│ single-flight (P)    │  │                      │  │                      │
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘

        ⑦ Critic loop: load cited source text (cache hit above),              
          embed claim, retrieve top-3 passages by cosine (pgvector),          
          grade with Haiku in batches of 10, persist verdict + passage        
        ⑧ unsupported_ratio = (UNSUPPORTED+CONTRADICTED) / verifiable         
        ⑨ if ratio > 0.15 AND round < 2: re-research FAILED claims only       
          (loop back to ② fan-out, smaller scope). Else: publish w/ badges    

┌────────────────────────────────────┐   ┌────────────────────────────────────┐
│ POSTGRES 16 + pgvector             │   │ ANTHROPIC API                      │
│ (B) schema  (P) embeddings/verdicts│   │ (P)                                │
├────────────────────────────────────┤   ├────────────────────────────────────┤
│ runs / dag_nodes / dag_levels      │   │ Haiku: planner, researcher         │
│ (all fan-in state lives here --    │   │   queries, passage extraction,     │
│ restart-survival, no in-memory     │   │   critic grading                   │
│ counters, ever)                    │   │ Sonnet: researcher synthesis,      │
│ embeddings for cosine retrieval    │   │   writer                           │
│ verdicts + evidence passages       │   │ called by planner / researcher /   │
│                                    │   │ writer / critic -- NEVER directly  │
│                                    │   │ by retrieval-service               │
└────────────────────────────────────┘   └────────────────────────────────────┘
```


### Built vs planned, in one line each

- **(B) built today:** all four containers + SearXNG (json format), Kafka topics,
  Postgres schema via Flyway, `retrieval-service` search (cache-aside + tiering) and
  quota counter, all three apps green on `/actuator/health`.
- **(P) planned:** extractor sidecar, concurrency patterns (token bucket Lua,
  single-flight), planner/fan-out/fan-in/sweeper, researcher/writer/critic loops,
  SSE page, evals — Weeks 2–4. CI/CD + k8s — Week 5.

---

## How a run flows, step by step

1. **Submit** — user POSTs a question to `control-plane`; a `run` row is created (`PENDING`).
2. **Plan** — Planner (Haiku) splits it into 6–8 flat sub-questions (no DAG edges this month)
   and fans them out to `research.subtasks`; a `dag_levels` row seeds the fan-in counter.
3. **Research ×6** — six RESEARCHER consumers each take one subtask: generate queries,
   search, tier-filter, extract top-5, pull candidate passages, synthesize with Sonnet,
   self-assess confidence. 90s / 25k-token budget; breach → emit a partial finding.
4. **Retrieval is the only door to the web** — every search/extract from any agent goes
   through `retrieval-service`: Redis cache-aside first, SearXNG/extractor only on miss,
   token bucket per domain, credits counted once per real upstream call.
5. **Findings** land on `research.findings`; progress events on `agent.events`.
6. **Fan-in** — control-plane increments `dag_levels.completed` per finding via
   `UPDATE … RETURNING`. Exactly one consumer sees `completed == expected` → triggers the
   Writer. The 15s sweeper releases levels past their deadline so one hung researcher
   can't strand the run. All state is in Postgres → restart-safe.
7. **Write** — WRITER (Sonnet) emits structured `Claim`s, exactly one `sourceId` each;
   `narrative` holds connective tissue only. This is what makes verification possible.
8. **Verify** — CRITIC re-checks every non-INFERENCE claim: load the cited source text
   (cache hit), embed the claim, retrieve top-3 passages by cosine from pgvector,
   grade with Haiku in batches of 10, persist verdict **plus the matched evidence passage**.
9. **Loop gate** — if `unsupported_ratio > 0.15` and round < 2, re-research the failed
   claims only (back to step 2's fan-out, smaller scope). Otherwise publish with badges.
10. **Deliver** — report persists to `runs.report`; the SSE page shows live agent progress
    and per-claim verdicts; clicking a badge reveals the stored evidence passage.

> Why Kafka here and not HTTP: planner→researchers→writer are long-running and must
> survive restarts (durable offsets + Postgres state). agents→retrieval-service stays
> HTTP because those are short cache-lookup-style calls.

---

## The pieces, honestly labelled

| Component | Port | Type | Status | What it is |
|---|---|---|---|---|
| `retrieval-service` | 8081 | Spring Boot | **built** (search + quota) | The librarian — search, extract, quota |
| `agent-service` | 8082 | Spring Boot | scaffold (`Application` class) | Hosts RESEARCHER / WRITER / CRITIC profiles (Weeks 2–3) |
| `control-plane` | 8083 | Spring Boot | built: schema + Flyway | REST + SSE, planner, fan-out/fan-in, budget |
| `common` | — | Java lib | empty | shared records/contracts, future use |
| `postgres` | 5432 | pgvector/pg16 | built | runs, DAG state, embeddings later |
| `redpanda` | 9092 | Kafka-compatible | built | bus for fan-out (`subtasks`) / fan-in (`findings`) / progress (`events`) |
| `redis` | 6379 | redis:7 | built | search cache, quota counter; later: token bucket, single-flight |
| `searxng` | 8080 | searxng/searxng | built | the metasearch engine behind `/search` |
| extractor sidecar | 8000 | Python/FastAPI | **planned** | clean-article extraction via trafilatura |

### Database schema (control-plane, Flyway `V1`)

```
runs  (uuid id PK · question · status PENDING→… · report jsonb, null until done)
 │
 ├──< dag_nodes    one row per sub-question:
 │                   id PK · run_id FK · level · depends_on · sub_question ·
 │                   status · finding jsonb
 │                   [depends_on/level intentionally unused — flat fan-out only]
 │
 └──< dag_levels   the fan-in counter (one row per run×level):
                     run_id FK · level · expected · completed · deadline_at
                     UPDATE … SET completed=completed+1 … RETURNING is the
                     whole mechanism — no in-memory counters, ever
```

---

## `retrieval-service` internals — what's finished

Request in → results out, with caching, trust-tiering, and a real credit counter.

**`POST /api/v1/search`, decision by decision:**

1. Normalize each query (lowercase, trim, collapse whitespace, keep quotes).
2. Build cache key: `search:v1:searxng:{sha256(normQuery | freshness)[0:16]}` → `GET` Redis.
3. **Hit** → deserialize cached raw results. **Miss** → call SearXNG
   (`GET /search?q=…&format=json`), spend **1 credit**, write raw results back, TTL 24 h.
4. Map raw results → `SearchResult` + trust tier (`SourceTierResolver`, YAML allowlist).
5. Filter `tier <= minTier`, **then** `limit(maxResults)` — order matters: limiting
   before filtering wastes cap slots on results that get thrown away.
6. Respond `{results, creditsSpent, cacheHits}`.

**Design decisions baked in:**
- Caches the **raw** SearXNG results *before* tiering/filtering, so tier edits apply
  instantly and one query serves every `maxResults`/`minTier` combination for one credit.
- That's why `maxResults`/`minTier` are **not** in the key — see the deviation note in
  `docs/PLAN.md`. `freshness` stays because it changes SearXNG's response.
- Quota is a daily counter (`quota:v1:{date}`, TTL 24 h), incremented on every real
  SearXNG call — including cache hits, by current decision.dsh web --no-open

---

## Where things actually stand

- **Built & tested:** containers + SearXNG json format, Kafka topics, Flyway schema,
  `retrieval-service` search with Redis cache-aside + tiering, quota counter, all three
  apps green on `/actuator/health`.
- **Scaffolded only:** `agent-service` and `control-plane` services.
- **Planned:** URL normalization, extractor sidecar, Session 4 concurrency patterns,
  researcher loop + fan-out/fan-in (Week 2), writer + Critic (Week 3), SSE page +
  evals (Week 4), CI/CD + k8s (Week 5).
