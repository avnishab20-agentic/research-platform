# The Story of This Codebase — 92: Check Yourself

*15 questions. If you can answer these, you understood the guide. Answers are collapsed
— try first, then peek.*

---

**Q1. The search cache key includes `freshness`, but `freshness` is never sent to
SearXNG. Why include it in the key at all — and why was it fixed *before* wiring the
feature?**

<details><summary>Answer</summary>
Because the key must contain everything that will <em>ever</em> change the upstream
response. `freshness` maps to SearXNG's <code>time_range</code>, so the day someone
wires it through, two same-words searches with different freshness must not share one
cache entry — they'd serve each other wrong results. Fixing the label before wiring the
feature means the collision bug can never go live (<code>SearchService.java:78-80</code>;
decision recorded in CLAUDE.md Session 7 and docs/PLAN.md:42-51).
</details>

**Q2. Tracing: `POST /api/v1/search` arrives, Redis is up, SearXNG container is dead,
and this query has never been searched. Walk the path end to end.**

<details><summary>Answer</summary>
Tomcat thread parses JSON into <code>SearchRequest</code> → controller delegates
(<code>RetrievalController.java:23</code>) → key minted, Redis GET returns null (miss)
→ <code>fetchResults</code> calls SearXNG via RestClient → connection fails → a
RestClientException-family error propagates (no try/catch anywhere) → Spring returns
HTTP 500. State afterward: nothing cached, no credit charged (INCR happens only after
a successful fetch, <code>SearchService.java:62-65</code>), retrying is safe.
</details>

**Q3. Why do both `SearchService` and `ExtractorClient` constructors carry
`@Qualifier`? What breaks if you delete just one of them?**

<details><summary>Answer</summary>
Two beans share the type <code>RestClient</code>, so every injection point must name
its bean. Deleting either qualifier makes Spring find two candidates for that
constructor parameter → ambiguous dependency → the whole context refuses to boot
(boot-time failure, not runtime).
</details>

**Q4. Tracing: a message appears on topic `research.subtasks` and Postgres is down.
Walk the path.**

<details><summary>Answer</summary>
Nothing happens, by design of today's codebase: no Java class anywhere has a Kafka
dependency or listener, so no consumer exists to notice the message. It sits in the
topic on Redpanda indefinitely. Postgres being down only affects control-plane boot /
Flyway. This is blueprint infrastructure awaiting Week 2 (verified: no spring-kafka in
any pom; topics created server-side per CLAUDE.md Session 3).
</details>

**Q5. Where exactly does a credit get charged, and why are cache hits free?**

<details><summary>Answer</summary>
Only in SearchService's cache-miss branch: after a successful SearXNG fetch,
<code>quotaService.recordSpend()</code> at <code>SearchService.java:65</code> (which
INCRs today's key, <code>QuotaService.java:21-25</code>). Hits skip that line because
the PLAN's done-when rule says a second identical query costs 0 credits — charging for
serving from cache would punish exactly the behavior caching exists to encourage.
</details>

**Q6. Why does the search flow cache the *raw* SearXNG response instead of the final
filtered list? What would have to change in the key if it were flipped?**

<details><summary>Answer</summary>
Because tier-filtering and maxResults-capping happen after the cache read
(<code>SearchService.java:69-74</code>), different callers with different knobs can
share one upstream fetch. If the filtered list were cached instead,
<code>maxResults</code> and <code>minTier</code> would have to be added to the sha256 input —
otherwise different requests would collide on one pre-filtered answer — and then
every knob combination would burn its own fresh credit. That regression is exactly
what docs/PLAN.md:42-51 warns about.
</details>

**Q7. A request sets `minTier: 2`. Which results survive?**

<details><summary>Answer</summary>
Tiers 1 and 2. Despite the name, minTier acts as the maximum allowed tier number:
tier 1 = best, so the filter is <code>tier() &lt;= minTier</code>
(<code>SearchService.java:71</code>).
</details>

**Q8. Who actually calls `extract()` in the Python sidecar at runtime — and on what
thread?**

<details><summary>Answer</summary>
A uvicorn worker thread (FastAPI runs plain <code>def</code> endpoints off the event
loop). Today those requests come from hand-run curl commands only;
<code>ExtractorClient.extract()</code> exists but has zero production callers yet.
Docker's healthcheck additionally knocks on <code>/health</code> every 10 seconds.
</details>

**Q9. The sidecar can only ever answer OK or PAYWALLED. Who owns UNREACHABLE,
ROBOTS_DENIED, and TOO_LARGE, and why were they split off?**

<details><summary>Answer</summary>
The Java side will own them, because they're properties of <em>fetching</em> a page
(connection failed, robots.txt disallowed, body over 200KB) — and fetching stays in
retrieval-service, which is the single quota boundary for all web access. The sidecar
never touches the network, so it cannot observe those conditions (recorded deviation,
CLAUDE.md Session 10).
</details>

**Q10. Two identical `/api/v1/search` calls at 09:00:00 and 09:00:30, quota started
the day at 1000. Give both responses' `creditsSpent`, `cacheHits`, and both
`/api/v1/quota` readings after each call.**

<details><summary>Answer</summary>
Call 1: creditsSpent 1, cacheHits 0 → quota reads 999. Call 2: creditsSpent 0,
cacheHits 1 → quota still 999 (hits don't charge). This exact sequence was verified
live in Session 10 and re-derived from code here.
</details>

**Q11. Three tables exist in Postgres. Who created them, who writes them, who reads
them?**

<details><summary>Answer</summary>
Flyway created them once from V1__init_schema.sql during control-plane boot
(runs/dag_nodes/dag_levels). Nobody writes them and nobody reads them: no JPA entity,
repository, or SQL query references them anywhere in the code. They're Week-2 props
(fan-in state per CLAUDE.md architecture decisions).
</details>

**Q12. You edit redis's compose command to add a new flag. The file is saved. What
does the running container now believe, and why?**

<details><summary>Answer</summary>
It still runs the OLD command: a container's startup command is fixed at creation.
Until you recreate it (<code>docker compose up -d redis</code>), live config and
compose file disagree silently — Session 10 hit exactly this with allkeys-lru.
Verify live state with CONFIG GET, never trust the YAML alone.
</details>

**Q13. Control-plane boots while Postgres is down vs retrieval-service boots while
Redis is down. Different outcomes — explain both.**

<details><summary>Answer</summary>
Control-plane dies at boot: its datasource connects eagerly and Flyway needs the DB
before serving anything. Retrieval-service boots fine: Lettuce connects lazily, so
Redis absence surfaces later as HTTP 500 on the first request that touches the cache
or quota. Eager failure = startup error; lazy failure = deferred request error.
</details>

**Q14. Why does the extract cache key normalize the URL (`ExtractCacheKey`) when the
search key doesn't normalize URLs at all?**

<details><summary>Answer</summary>
Because extract keys are built from caller-supplied URLs, where cosmetic variants
(www., utm_*, #fragment, trailing /) point at the same article and must share one
cache entry — four spellings, one fetch. Search keys are built from query *text*, not
URLs; there the normalization that matters is whitespace/case of the query itself
(<code>SearchService.java:83-87</code>). Each key normalizes exactly the input that
could fork it.
</details>

**Q15. Name three deliberate deviations from the original PLAN recorded in the docs —
not bugs, decisions.**

<details><summary>Answer</summary>
Any three of: (1) dropped <code>maxResults</code>/<code>minTier</code> from the search
cache-key hash because raw results are cached pre-filtering (docs/PLAN.md:42-51);
(2) sidecar status vocabulary narrowed to OK|PAYWALLED, fetch-time statuses moved to
Java (CLAUDE.md Session 10); (3) <code>freshness</code> kept in the key but never wired
to SearXNG time_range — unplanned feature refused (Session 7); (4) CI pipeline moved
from Week 1 to Week 5 (docs/PLAN.md:103-107); (5) DAG dependency columns stay unused —
flat fan-out only (CLAUDE.md scope cuts).
</details>
