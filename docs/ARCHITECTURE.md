# Architecture

How the research platform is built: which part does what, how they talk to
each other, where data is stored, and why it is designed this way.

If you only read one section, read [One question, start to finish](#one-question-start-to-finish).

---

## The big picture

```
                         Browser (web page served by retrieval-service)
                            │ ask a question        ▲ live progress (SSE)
                            ▼                       │
                ┌─────────────────────────────────────────┐
                │ control-plane :8083                     │
                │ planner · fan-out · fan-in · SSE stream │
                └─────────────────────────────────────────┘
                   │ sub-questions            ▲ answers
                   ▼                          │
                ┌─────────────────────────────────────────┐
                │ Kafka (Redpanda locally, Azure Event    │
                │ Hubs in the cloud): message queues      │
                └─────────────────────────────────────────┘
                   │                          ▲
                   ▼                          │
                ┌─────────────────────────────────────────┐
                │ agent-service :8082                     │
                │ RESEARCHER · WRITER · CRITIC            │──── DeepSeek (AI model)
                └─────────────────────────────────────────┘
                   │ HTTP: "search this", "fetch these pages"
                   ▼
                ┌─────────────────────────────────────────┐
                │ retrieval-service :8081                 │
                │ search · fetch · extract · cache · limit│
                └─────────────────────────────────────────┘
                   │              │               │
                   ▼              ▼               ▼
               SearXNG         extractor        Redis
               (search)        (HTML → text)    (cache, limits)

   Postgres + pgvector: shared by control-plane and agent-service
   (runs, sub-questions, claims, verdicts, page passages as vectors)
```

---

## One question, start to finish

**1. You submit a question.** `POST /api/v1/runs` reaches
`RunController` in control-plane, which calls `PlannerService.submit()`. It
first checks the daily limit (20 runs a day), then saves a `runs` row with
status `RUNNING`.

**2. The planner splits it.** One DeepSeek call turns the question into 5
sub-questions that can each be answered on their own. Each becomes a
`dag_nodes` row, and one `dag_levels` row records "5 expected, 0 completed,
deadline in 3 minutes".

**3. Fan-out.** Each sub-question is published as a `ResearchSubtask` message on
the `research.subtasks` Kafka topic. The message key is the sub-question's own
id. That spreads the 5 messages across different partitions (Kafka's parallel
lanes), so 5 researchers can pick them up at once.

**4. Research.** In agent-service, `ResearchSubtaskListener` receives a message
and calls `ResearcherService.research()`, which:
1. asks DeepSeek for 3 search queries;
2. searches through retrieval-service and removes duplicate results;
3. downloads the top 5 pages through retrieval-service (clean text only);
4. splits each page into ~800-character passages, turns each into a vector
   (an embedding) and stores it (see [RAG](#rag-finding-the-right-passage));
5. finds the 4 passages closest in meaning to the sub-question;
6. asks DeepSeek to answer **using only those passages**, or reply
   `UNANSWERABLE`;
7. scores confidence from the best source's trust tier (tier 1 = 0.95 …
   tier 4 = 0.4, or 0 if unanswerable);
8. publishes a `ResearchFinding` on `research.findings`.

A researcher never throws on a problem such as a failed search, no budget left
or time running out. It returns a `PARTIAL` finding with whatever it has.

**5. Fan-in: knowing when everyone is done.** control-plane's
`FindingListener` → `FanInService.recordFinding()` saves the finding and runs:

```sql
UPDATE dag_levels SET completed = completed + 1
WHERE run_id = ? AND level = 0
RETURNING completed, expected
```

Postgres runs these updates one at a time for the same row, so even if 5
findings arrive in the same millisecond, **exactly one** of them sees
`completed == expected`. That one publishes `RunReady` on `run.ready`.

If a researcher hangs, `DeadlineSweeper` (every 15 seconds) finds runs past
their deadline, marks them `PARTIAL`, and publishes `RunReady` anyway, so one
stuck researcher can't block the run forever.

**6. Write claims.** `RunReadyListener` → `WriterService.write()` asks
DeepSeek to break each finding into at most 8 short claims. Each claim is one
fact, typed as FACT, FIGURE, QUOTE or INFERENCE, and tied to **exactly one**
source URL. Claims go into the `claims` table, then `ClaimsReady` is published
on `claims.ready`.

**7. Fact-check.** `ClaimsReadyListener` → `CriticService.verify()`:
1. re-downloads every cited source (proving it is still reachable);
2. for each claim, retrieves the 3 passages closest to the claim's text;
3. asks DeepSeek to grade claims in batches of 15 (SUPPORTED, PARTIAL,
   UNSUPPORTED or CONTRADICTED) and to quote the exact sentence it used.
   INFERENCE claims are not graded, since they have no source sentence to check;
4. **second round:** for each failed claim (up to 3), `ClaimCorrector` searches the web
   again, then either rewrites the claim to say only what a real passage says,
   or removes it. A rewrite is graded again by the same independent grader;
5. `ConclusionWriter` writes a short conclusion using **only** claims that
   passed, with each sentence citing them;
6. sets the run to `VERIFIED`, or to `UNVERIFIED` if more than 15% of the
   claims shown failed.

**8. Watching it live.** The browser opens `GET /api/v1/runs/{id}/events`, an
SSE (Server-Sent Events) connection the server keeps open. A shared scheduler
in `RunController` checks Postgres once a second and sends only what changed:
new activity lines (from `run_events`) and progress ("3 of 5 done"). The stream
closes when the run finishes. The final report comes from
`GET /api/v1/runs/{id}/report`.

---

## Kafka topics

| Topic | Sent by | Read by | Partitions |
|---|---|---|---|
| `research.subtasks` | planner (control-plane) | RESEARCHER | 12 |
| `research.findings` | RESEARCHER | fan-in (control-plane) | 6 |
| `run.ready` | fan-in | WRITER | 3 |
| `claims.ready` | WRITER | CRITIC | 3 |
| `agent.events` | nobody | nobody | 3 (created but unused, kept for later) |

Each listener has its own consumer group, handles one message at a time, and
may take up to 10 minutes per message before Kafka assumes it died. The reasons
behind each setting are in [KAFKA.md](KAFKA.md).

---

## Database tables (Postgres)

Created by Flyway migrations in `control-plane/src/main/resources/db/migration/`.
Nothing is created by Hibernate: the schema always comes from these files.

| Table | Holds |
|---|---|
| `runs` | one row per question: text, status, final conclusion (JSON) |
| `dag_nodes` | one row per sub-question: status and the finished answer (JSON) |
| `dag_levels` | the fan-in counter: expected, completed, deadline |
| `run_usage` | how many searches and AI calls a run has used (for the per-run limits) |
| `run_events` | the plain-English activity feed shown in the browser |
| `sources` | every web page a run used, with its trust tier and text |
| `claims` | every claim: text, kind, its one source, and whether it was rewritten or removed |
| `claim_verdicts` | the grade for each claim plus the exact evidence sentence |
| `vector_store` | page passages as embeddings, tagged with their run and URL |

`dag_nodes` has `depends_on` and `level` columns that are deliberately unused.
Every sub-question is independent for now.

## Redis keys (retrieval-service)

| Key | Holds | Kept for |
|---|---|---|
| `search:v2:{provider}:{hash}` | raw search results for one query | 24 hours |
| `lock:search:…` | "someone is already fetching this query" | 30 seconds |
| `extract:v1:{hash}` | one page's extracted text (OK or PAYWALLED only) | 7 days |
| `quota:v1:{date}` | searches spent today | 24 hours |
| `ratelimit:v1:{host}` | token bucket for one website (a Lua script updates it) | until it would be full again |

The version in each key (`v1`, `v2`) exists so that a format change never reads
old data: bump the version and the old keys simply expire.

---

## RAG: finding the right passage

RAG (Retrieval-Augmented Generation) means "look up the relevant text first,
then let the AI answer from it". `PassageStore` in agent-service does both
halves, and both the Researcher and the Critic use it:

- **Index:** split a page into ~800-character passages, turn each into an
  embedding (a list of numbers that captures its meaning) and store it in
  `vector_store`, tagged with the run id.
- **Retrieve:** turn a question (or a claim) into an embedding and return the
  k stored passages closest to it. Results are always filtered to the current
  run, so runs never see each other's pages.

Embeddings are made by a **local ONNX model** (`spring-ai-starter-model-transformers`),
not a paid API: free, no key needed, and the same text always gives the same
numbers, which keeps tests repeatable.

---

## Search: cache, lock, quota

`SearchService.search()` handles each query in one of four ways:

1. **Cached:** return it for free.
2. **Not cached, and this request wins the lock:** check the daily quota, call
   SearXNG (or Tavily, if a `TAVILY_API_KEY` is set), cache the result, release
   the lock. Costs 1 credit.
3. **Someone else holds the lock:** wait (up to 5 seconds) for them to fill the
   cache, then read it. Free. This is how 20 identical requests turn into 1
   real search.
4. **They never finished:** fetch anyway. A duplicate cost beats returning
   nothing.

Empty results are never cached, because a blocked search engine returns zero
results and caching that would hide the query for 24 hours.

Results are then labelled with a **trust tier** from a domain list in config
(1 = official, e.g. `rbi.org.in`; 2 = established news; 3 = unknown;
4 = low-trust patterns such as `*.blogspot.*`) and filtered.

## Extract: getting clean page text

`ExtractService` handles each URL on its own virtual thread:
cache → per-website rate limit (waits for a token) → `PageFetcher` downloads the
HTML (only `http`/`https`, never a private-network address, at most 2 MB) → the
Python extractor strips menus and ads → cache the result.

A failed URL never breaks the batch. It comes back with a status instead:
`UNREACHABLE`, `TOO_LARGE`, `RATE_LIMITED`, `PAYWALLED`, `BLOCKED_SCHEME` or
`BLOCKED_PRIVATE_NETWORK`. Only `OK` and `PAYWALLED` are cached, because they
describe the page itself; the others describe a temporary problem.

---

## Design decisions (and why)

| Decision | Why |
|---|---|
| **Fan-in counter in Postgres, not a Java variable** | A variable is lost on restart and not shared between copies of a service. The database row survives both. [Proven with a real `kill -9`](progress/2026-09-22l-restart-survival-demo.md). |
| **Three agent roles in one app (Spring profiles)** | They differ mostly in which prompt runs, not in how they deploy. One app is one image and one config to keep right. |
| **Only retrieval-service touches the web** | One place counts search spending, caches, and stays polite to websites. |
| **Claims, not paragraphs** | A paragraph with footnotes can't be checked sentence by sentence. A claim tied to one source can. |
| **The critic stores the evidence sentence, not just a grade** | The sentence is what lets a person check the grade. |
| **Kafka for slow steps, HTTP for quick lookups** | Kafka keeps work safe across crashes; a cache lookup doesn't need that. |
| **Every limit is config, not code** | Tuning is a config edit and a restart. One switch (`ENFORCE`/`SHADOW`) turns enforcement on or off. |
| **A failed run is still shown** | Shown as `PARTIAL` or `UNVERIFIED`. Hiding bad output would hide the failures this project exists to expose. |
| **DeepSeek for the AI model** | Chosen for cost. The critic's grading quality is the known trade-off, and the first thing to revisit if the fabrication eval stays low. |

---

## Local vs cloud

| | On your laptop | On Azure |
|---|---|---|
| Kafka | Redpanda container | Azure Event Hubs (Kafka-compatible) |
| Postgres | `pgvector/pgvector:pg16` container | Azure Database for PostgreSQL |
| Redis | Redis container | Azure Cache for Redis |
| Services | `mvn spring-boot:run` | pods on AKS, deployed by GitHub Actions |
| Entry point | `localhost:8081` (page) + `:8083` (API) | one HTTPS address through a Caddy front door |

Setup details are in [DEPLOYMENT.md](DEPLOYMENT.md).
