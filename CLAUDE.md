# Research Platform

Multi-agent research system: a question is decomposed into sub-questions, researched
in parallel over Kafka, synthesized into a report, then **verified claim-by-claim**
against the sources it cites.

The verification is the point of the project. Never cut it.

---

## Scope: 1 month, 3 services

| Service | Port | Responsibility |
|---|---|---|
| `retrieval-service` | 8081 | Search, fetch, extract, cache, rate limit. The quota boundary. |
| `agent-service` | 8082 | RESEARCHER / WRITER / CRITIC as Spring profiles. Kafka consumers. |
| `control-plane` | 8083 | REST + SSE + orchestration (planner, fan-out, fan-in, budget). |

Plus `common/` (shared records, no main class) and two sidecar containers
(`extractor` = Python/trafilatura, `searxng`).

### Explicitly cut — do not build these
- DAG dependencies (flat fan-out only; keep the `depends_on`/`level` columns unused)
- Reconciler / contradiction detection
- Multi-provider search routing (SearXNG only)
- CI/CD (within weeks 1-4), Langfuse
- Rich UI (one plain SSE page only)
- Auth, multi-user, billing (within weeks 1-4 — auth/rate-limiting return in Week 5)

### Do not add (weeks 1-4)
- New services. Anything that feels like a new service is a Spring profile or a class.
- New dependencies without asking first.
- Kubernetes, service mesh, API gateway, Terraform, VM deployment.

### Week 5 — Kubernetes + KEDA (confirmed, not cut)
Separate ~15-20 hr phase, only after weeks 1-4 are complete and working on Compose.
Full build order lives in `docs/PLAN.md`. Runs on a rented cloud VM (k3s + KEDA),
**not on the local machine** — the 8GB MacBook Air budget below is for weeks 1-4 only.
Covers: k3s, KEDA ScaledObject on `agent-service` (consumer-lag scaling), ingress +
public URL, JWT auth on `control-plane`, 3-layer rate limiting (Traefik + per-domain
token bucket + per-user daily run cap). Explicitly skips Eureka (k8s has service
discovery built in) and building an OAuth2 authorization server.

---

## Stack

Java 21 · Spring Boot 3.3.x · **Maven** (multi-module) · Spring AI 1.0
Redpanda (Kafka API) · Postgres 16 + pgvector · Redis · SearXNG (self-hosted)
Claude Haiku (extraction, claim grading) + Sonnet (synthesis)

---

## Conventions

- **Records, not Lombok** for DTOs and agent contracts
- **`RestClient`**, not `RestTemplate`, for outbound HTTP
- **Flyway migration first**, then the entity to match. Never `ddl-auto`.
- **Constructor injection only.** No `@Autowired` on fields.
- `@Transactional` on the service layer, not repositories
- Never expose a JPA entity as an API response — map to a record
- `@ConfigurationProperties` records over scattered `@Value`
- `spring-boot-maven-plugin` in parent `<pluginManagement>` only, declared
  per-service. **Never in `common/`** — it breaks the dependency jar.
- Integration tests with Testcontainers over mocks

---

## Architecture decisions (the why — do not silently revise these)

**Fan-in uses a Postgres counter, not Kafka Streams windowing.**
Subtasks run 5–90s with high variance, so windows must be uselessly generous and
stuck windows are miserable to debug. `UPDATE ... RETURNING` on `dag_levels`;
exactly one consumer observes `completed == expected` and triggers the writer.
A sweeper releases levels past their deadline so one hung node can't strand a run.

**Fan-in state must NOT live in a ConcurrentHashMap or AtomicInteger.**
That breaks restart-survival, which is a headline feature. It must be in Postgres.

**Agent types are Spring profiles of `agent-service`, not separate deployables.**

**Retrieval is the quota boundary.** All search credits and all caching happen in
`retrieval-service`. No other service calls a search API directly.

**The writer emits structured claims, not prose.** Each `Claim` carries exactly one
`sourceId`. Prose is rendered from claims. The `narrative` field carries connective
tissue only — no facts. Free prose with footnotes makes verification impossible.

**The Critic re-fetches sources and stores the matched evidence passage**, not just
a verdict. The passage is what makes the verdict checkable.

**Kafka for long-running/durable hops. HTTP for cache lookups.**
orchestrator↔researchers = Kafka. agents→retrieval-service = HTTP.
Don't put everything on Kafka.

---

## Traps that have already cost time

- `spring.kafka.consumer.max-poll-interval-ms` defaults to 5 min. Research subtasks
  exceed it, the consumer gets evicted mid-work, and work silently duplicates with
  no error. Set it to 600000 and `max-poll-records: 1`.
- SearXNG returns HTML by default. `json` must be added to `search.formats` in
  `settings.yml` or every call fails confusingly.
- Use the `pgvector/pgvector:pg16` image, not stock postgres.
- Cache keys are versioned (`search:v1:`). Bump the version instead of invalidating.

---

## Working agreement

The point of this project is for me to learn agentic systems, not to have them
written for me.

**You write:** Maven/module scaffolding, `docker-compose.yml`, SearXNG config,
Flyway DDL, the Python extractor sidecar, adapter/mapping classes, tests, the SSE page.

**I write, so ask before implementing:** the fan-in logic, the deadline sweeper,
the Critic loop, cache key normalization, all agent prompts.

If I ask you to explain rather than implement, explain — don't write the code.
Before generating anything non-obvious, say what approach you're taking and why.

### How I like to work with you

- I type, click, and run everything myself by default — Maven fields, docker-compose
  content, git commands, all of it. Guide me step by step and explain what/how/why
  before I do each thing, don't just do it for me.
- Exceptions are one-off and explicit only (e.g. I once asked you to `brew install`
  Java and edit `~/.zshrc` directly — that doesn't carry forward to anything else).
- When I say "check and fix it" (e.g. YAML indentation, a broken pom) — that's
  explicit permission for that specific fix. Go ahead and edit directly, then
  explain what was wrong and what changed. Don't ask again for the same class of
  fix within the same session.
- I'm a backend Java/Spring dev (~4.5 yrs) but new to Docker/docker-compose,
  multi-module Maven, and agentic systems — explain infra concepts from first
  principles (ELI5 is fine, even preferred) rather than assuming I know the jargon.
- **Default explanation style: plain, layman, conversational — like ChatGPT's
  "explain it simply" mode, not a textbook.** Strip jargon or define it inline the
  first time it's used. Use everyday analogies for new concepts (e.g. "a Kafka
  consumer group is like a team splitting up a to-do list"). Short sentences,
  plain words, no wall of dense paragraphs. Only go precise/technical-depth when
  I explicitly ask for the deeper version. As of 2026-08-18 I've only grasped
  ~20-30% of what's been built so far — so re-explain past decisions in this
  simpler style whenever they come up again, don't assume they landed the first time.
- Give me honest progress checks against `docs/PLAN.md` when I ask "are we
  lagging" — a real status table, not reassurance.
- Some modules I want to build entirely solo once I've learned the pattern once
  (e.g. I built `retrieval-service` with your guidance, then asked to do
  `agent-service` on my own) — respect that without re-offering to do it for me.

---

## Definition of done for a session

`docker compose up` is green, the module compiles, the test passes, and I can
explain every line that was added. Commit at each working step.

---

## Session Log

### Session 1 (2026-08-09) — Maven skeleton + docker-compose
**Done:**
- Root `pom.xml` (aggregator, packaging=pom), groupId `com.comeback.researchplatform`
- `common` module wired (plain jar, no Spring Boot plugin)
- `retrieval-service` module created via IntelliJ Spring Initializr — Spring Boot **4.1.0** GA (watch out: wizard defaulted to `4.1.1-SNAPSHOT`, had to correct)
- Root pom now has `<dependencyManagement>` importing `spring-boot-dependencies:4.1.0` BOM + `<pluginManagement>` pinning `spring-boot-maven-plugin:4.1.0` — modules inherit versions from root, not from `spring-boot-starter-parent` directly
- `docker-compose.yml`: postgres (`pgvector/pgvector:pg16`), redis, redpanda (`v25.3.1`, dual internal/external listener), searxng — all 4 healthy
- `searxng/settings.yml`: added `search.formats: [html, json]` — confirmed working via curl
- `mvn clean install` passes, exit 0
- GitHub remote connected: `github.com/avnishab20-agentic/research-platform`, pushed

**Not done yet (rest of Session 1 per PLAN.md):**
- `agent-service`, `control-plane` modules
- Flyway baseline (`runs`, `dag_nodes`, `dag_levels` — DAG columns intentionally unused, flat fan-out only)
- Kafka topics (`research.subtasks`, `research.findings`, `agent.events`)
- `/actuator/health` check across all 3 apps

**Next session starts with:** creating `agent-service` module (user doing this one solo — knows the pattern now: Maven/Java21/Boot 4.1.0 explicit, watch for double-nested folder, fix `<parent>` to point at root pom, add to root `<modules>`).

### Session 2 (2026-08-10) — agent-service + control-plane skeletons, docs cleanup
**Done:**
- `agent-service` module created (mostly solo) — same `<parent>` bug as before (wizard pointed at `spring-boot-starter-parent` instead of root pom), fixed
- `control-plane` module created (mostly solo) — wizard only offered `spring-boot-starter-webflux`; discussed SSE via WebFlux vs classic `webmvc`/`SseEmitter` tradeoff, decided **`webmvc`** for consistency with the other two services (no reactive paradigm needed for one plain SSE page)
- `control-plane` registered in root `pom.xml`'s `<modules>` list
- `control-plane/pom.xml` fixed twice: `<parent>` pointed at wrong target, then a pasted stray `>` (`</dependency>>`) broke the XML parse — both resolved
- `control-plane` pom gained `spring-boot-starter-data-jpa`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime) — this is the module that will own Postgres for fan-in state
- `LEARNING.md`/`PLAN.md` duplication resolved: root copies had newer content (Security + Architecture-decisions sections in LEARNING.md; Week 5 K8s+KEDA section in PLAN.md), merged into `docs/` copies, root duplicates deleted — `docs/` is now the single source of truth
- Added a layman-friendly `README.md` to all 4 modules (`common`, `retrieval-service`, `agent-service`, `control-plane`) — what/how/why/why-only-this for each, honest "skeleton only" status
- Diagnosed a `control-plane` build failure: NOT a pom problem — adding JPA/Postgres deps means `ControlPlaneApplicationTests.contextLoads` now boots a real `DataSource` bean, and `application.properties` has no `spring.datasource.*` yet → `Failed to determine a suitable driver class`. Expected next step, not a bug.

**Not done yet:**
- `spring.datasource.*` + `spring.jpa.hibernate.ddl-auto=validate` in `control-plane/application.properties` (values come from `docker-compose.yml`'s postgres service)
- Flyway migration SQL for `runs`, `dag_nodes`, `dag_levels` (Claude's task per the working agreement, queued once datasource config is in)
- Kafka topics (`research.subtasks`, `research.findings`, `agent.events`)
- `/actuator/health` check across all 3 apps
- `retrieval-service` package typo (`com.comback` → `com.comeback`) still unfixed, left as user's call

**Next session starts with:** adding `spring.datasource.*` to `control-plane/application.properties`, bringing docker-compose back up (`docker compose up -d`, user shut all 4 containers down at end of this session), then re-running `mvn -pl control-plane -am test` to confirm it goes green before writing the Flyway migrations.

### Session 3 (2026-08-13) — Session 1 closed out: Flyway, Kafka topics, actuator health, package rename
**Done:**
- `control-plane/application.properties` got `spring.datasource.url/username/password` (pointing at the Compose postgres service) + `spring.jpa.hibernate.ddl-auto=validate` — fixed the `Failed to determine a suitable driver class` test failure from Session 2
- **Flyway silently not running, zero tables created, no error** — root cause: Spring Boot 4.x split autoconfiguration into per-feature modules, and `flyway-core` alone no longer triggers Flyway's autoconfiguration in 4.x. Fix: swapped `flyway-core` for `spring-boot-starter-flyway` in `control-plane/pom.xml` (kept `flyway-database-postgresql`). Confirmed via full Flyway log lines after the fix.
- `V1__init_schema.sql` written (`runs`, `dag_nodes`, `dag_levels`, DAG columns intentionally unused) — hit one typo (`REATE INDEX` missing the leading `C`), fixed, then `mvn -pl control-plane -am test` passed clean and `\dt` confirmed all 4 tables including `flyway_schema_history`
- Kafka topics created via `rpk topic create` in the `research-redpanda` container: `research.subtasks` (12 partitions), `research.findings` (6), `agent.events` (3) — confirmed via `rpk topic list`
- **Port 8080 conflict**: none of the 3 Spring apps had `server.port` set, so all defaulted to `8080`, colliding with the `searxng` container's legitimate `8080:8080` mapping. Fixed by setting `server.port=8081/8082/8083` per the port table, matching `retrieval-service`/`agent-service`/`control-plane`.
- **`agent-service`/`control-plane` returning genuine 404 on `/actuator/health`, IntelliJ dashboard saying "actuator is not configured"** — ruled out pom/dependency issues (`dependency:tree` showed actuator correctly resolved on both). Root cause: the *running* IntelliJ processes were stale JVMs started before the actuator dependency was added — a JVM's classpath is fixed at launch, so editing the pom or doing "Download Sources and Update" doesn't reach a process that's already running. Confirmed via `lsof -p <pid>` showing zero actuator jars on the live classpath, then confirmed the fix by running `agent-service` fresh via `mvn -pl agent-service spring-boot:run`, which logged `Exposing 1 endpoint beneath base path '/actuator'` immediately. Real fix: stop both run configs, **Reload All Maven Projects** in IntelliJ (a full re-resolve, stronger than "Download Sources and Update"), restart. All 3 apps now report `{"status":"UP"}`.
- `retrieval-service` package typo fixed: `com.comback.researchplatform` → `com.comeback.researchplatform`, moved via `git mv`, package declarations updated, module README's stale typo callout replaced, rebuilt clean (`mvn -pl retrieval-service -am test`, all green)

**Session 1 is now fully closed** — PLAN.md's "done when" (all containers healthy, all 3 apps green on `/actuator/health`) is met.

**Next session starts with:** Week 1 Session 2 — `retrieval-service` endpoints (`POST /api/v1/search`, `POST /api/v1/extract`, `GET /api/v1/quota`), the versioned cache key scheme, URL/query normalization rules, and the YAML source-tiering allowlist, per `docs/PLAN.md`.

### Session 4 (2026-08-16) — retrieval-service DTOs + stub controllers + source tiering v1 built and tested
**Done:**
- `dto` package created under `retrieval-service` with 7 records matching `docs/PLAN.md`'s endpoint contracts: `SearchRequest`, `SearchResult`, `SearchResponse` (`results`, `creditsSpent`, `cacheHits`), `ExtractRequest`, `Document` (`url`, `text`, `tier`, `status`), `ExtractResponse`, `QuotaResponse` — user wrote all of these solo, guided
- `web` package created with `RetrievalController` — all three endpoints (`POST /api/v1/search`, `POST /api/v1/extract`, `GET /api/v1/quota`) stubbed to return empty/placeholder data, proving the HTTP contract before real logic lands
- **New build trap found: root `pom.xml`'s `<java.version>21</java.version>` property was inert.** Since this project's root pom is a custom aggregator (not `spring-boot-starter-parent`), nothing wired that property into `maven-compiler-plugin`. Compilation silently fell back to `-source 8`, which only surfaced once real code used a Java 16+ feature (records) — `class Foo(...)` errors early on were separate (see below), but even after fixing those to proper `record` syntax, compilation failed with "records are not supported in -source 8". Fix: added `<maven.compiler.release>${java.version}</maven.compiler.release>` to the root pom's `<properties>`.
- **New build trap found: IntelliJ's Run button doesn't auto-sync with pom.xml edits.** After the `maven.compiler.release` fix, `mvn compile` from the terminal worked immediately, but IntelliJ's own Run button kept failing with the same `-source 8` error — it uses its own cached per-module Language Level setting, populated at Maven import time, not live-tracking the pom. Fix recommended: **Settings → Build Tools → Maven → Runner → "Delegate IDE build/run actions to Maven"**, so the Run button always shells out to real Maven instead of drifting out of sync.
- Along the way, fixed several hand-written mistakes as a learning pass: `class` used instead of `record` (records require the `record` keyword, not a parenthesized `class`), a wrong `List` import (`com.sun.tools.javac.util.List` — the compiler's own internal list type — instead of `java.util.List`), a wildcard import missing the `.dto` subpackage (Java wildcard imports don't reach into subpackages), a `package` statement mistakenly given a wildcard, and a missing semicolon
- Docker daemon was found stopped (left down from a prior session), which surfaced as `control-plane`'s `contextLoads` test failing with `Connection to localhost:5432 refused` — not a code issue; resolved by starting Docker Desktop and `docker compose up -d`
- Verified all three stub endpoints end-to-end after all fixes: `GET /api/v1/quota` (browser) → `{"creditsRemaining":1000}`, `POST /api/v1/search` (Postman) → `{"results":[],"creditsSpent":0,"cacheHits":0}`, `POST /api/v1/extract` (curl) → `{"documents":[]}`
- Confirmed SearXNG's real JSON response shape via live curl against the running container (`query`, `results[]` with `url`/`title`/`content`/etc.) — `tier` has no SearXNG equivalent, it's purely our own concept; also confirmed SearXNG ignores any "max results" concept, so `maxResults` from `SearchRequest` must be applied client-side
- Source tiering v1 (YAML domain allowlist, per `docs/PLAN.md`) built end to end:
  - Researched and populated real Indian domains in `application.yml`'s `source-tiers` config (tier1: `pib.gov.in`, `rbi.org.in`, `isro.gov.in`; tier2: `thehindu.com`, `indianexpress.com`, `ndtv.com`; tier4-patterns: `*.blogspot.*`, `*wordpress.com`) — fixed two bugs directly (user's explicit "fix it"): missing space after YAML `-` list markers, and `tier4` key renamed to `tier4-patterns` to match the record field via Spring relaxed binding
  - `SourceTierProperties` (`@ConfigurationProperties(prefix = "source-tiers")` record) — user initially created it as `config.java` sitting directly in `retrievalservice/` with a `package ...config;` declaration; fixed via IntelliJ Refactor (Rename → `SourceTierProperties.java`, Move → `config/` subpackage) to satisfy Java's filename-matches-public-type-name and package-matches-directory rules
  - Found and fixed an unrelated typo in the same compile pass: `ExtractResponse.java` had `public yeahrecord ExtractResponse(...)` (stray paste) instead of `public record`
  - `@ConfigurationPropertiesScan` added to `RetrievalServiceApplication`
  - `SourceTierResolver` (`tier` package, constructor-injected `SourceTierProperties`) written by the user across several guided rounds — algorithm: extract host via `URI.create(url).getHost()` → strip leading `www.` → lowercase → exact-match tier1 → exact-match tier2 → glob-match tier4Patterns (`*` → `.*` regex conversion) → default tier 3. Fixed several real bugs along the way: `tier.Properties` typo instead of `tierProperties`, `return 3` misplaced inside the `for` loop (would've returned 3 after checking only the first pattern instead of all of them), and a method (`matchesPattern`) illegally nested inside another method (Java doesn't allow that — methods can only be declared directly inside a class body) with a case-mismatched parameter name (`Pattern` vs `pattern`)
  - `SourceTierResolverTest` written (by Claude, at explicit request) — plain JUnit 5, no Spring context needed since the resolver only depends on a plain record — 5 tests covering tier1 exact match, tier2 exact match + `www.` stripping, both tier4 patterns, and the tier3 default. All passing.
- Discussed constructor injection vs `@Autowired` field injection in depth (immutability via `final` fields, visible dependency list, plain-Java testability, fail-fast on missing/circular deps) and the legitimate remaining use cases for field/setter injection (optional deps, objects Spring doesn't construct itself, test classes, breaking a genuine circular-dependency design smell)
- Did an honest status check against `docs/PLAN.md`'s full 4-week scope: Week 1 Session 1 done, Session 2 ~25-30% (tiering done+tested; `/search`/`/extract`/`/quota` still stubs; cache keys, URL/query normalization not started), Sessions 3-5 and all of Weeks 2-4 not started
- Clarified scope boundary: KEDA/Karpenter/Kubernetes autoscaling questions (from the user's day-job interview prep) are explicitly out of this project's scope per `CLAUDE.md`'s "Do not add: Kubernetes..." — this project (docker-compose only) will build real depth on Kafka/Spring-Kafka consumer internals (`max.poll.interval.ms` rebalance/duplication trap, idempotent Postgres-backed fan-in, listener concurrency, partition counts) but not K8s-specific autoscaling mechanics

**Not done yet:**
- Real `RestClient` → SearXNG integration for `/api/v1/search` (still a stub)
- `/api/v1/extract`'s real body — blocked on the Python extractor sidecar, which is Session 3's job, not yet built
- Versioned cache key scheme, URL/query normalization rules (user's task per the working agreement)

**Next session starts with:** wiring `POST /api/v1/search` to actually call SearXNG via `RestClient`, mapping results into `SearchResult` with `tier` filled by the now-working `SourceTierResolver`, and applying `maxResults` client-side since SearXNG ignores it.

### Session 5 (2026-08-17) — live SearXNG search integration
**Done:**
- Added `SearxngClientConfig`, providing a constructor-injected `RestClient` bean configured from `searxng.base-url` in `retrieval-service/application.yml`.
- Added SearXNG response records: `SearxngSearchResponse` (`results`) and `SearxngResult` (`url`, `title`, `content`), matching the JSON returned by the local SearXNG instance.
- Implemented `SearchService.fetchResults(String)`: `GET /search?q={query}&format=json` through the SearXNG `RestClient`.
- Implemented `SearchService.search(SearchRequest)`: fans out over `queries`, maps each SearXNG result to the API-owned `SearchResult` (`content` → `snippet`), resolves the source tier via `SourceTierResolver`, filters against `minTier`, globally caps results at `maxResults`, and reports one credit per query.
- Wired `RetrievalController` through constructor injection to delegate `POST /api/v1/search` to `SearchService.search(request)`.
- Verified the full path against the real local SearXNG container with a curl request for `Spring Boot REST client`; response contained real results with resolved tiers, `creditsSpent: 1`, and `cacheHits: 0`.
- Ran `mvn -pl retrieval-service -am test`; all 6 tests passed.

**Started but not complete:**
- Began the next cache feature by adding query-normalization helpers and injecting `StringRedisTemplate` into `SearchService`. There is no Redis read/write, TTL, JSON serialization, cache-hit accounting, or cache-aside behavior yet; caching is not part of this completed SearXNG integration step.

**Not done yet:**
- Complete the versioned cache-key scheme and manual cache-aside Redis flow (24-hour search TTL) per `docs/PLAN.md`.
- `/api/v1/extract`'s real body — blocked on the Python extractor sidecar, which is Session 3's job, not yet built.

**Next session starts with:** either commit the completed SearXNG integration as its own working step, or continue the separate Redis cache-aside feature from the partial setup above.

### Session 6 (2026-08-18) — full retrieval-service walkthrough + confirmed cache-aside is done
**Done:**
- Read every file in `retrieval-service` (all `dto`, `config`, `tier`, `search`, `web` classes, both `application.yml`/`application.properties`, and `SourceTierResolverTest`) and gave a full class-by-class, method-by-method explanation of the module — what each piece does and why, tied back to the project's architecture decisions (quota boundary, versioned cache keys, tier filtering order).
- In the course of that review, confirmed the Redis cache-aside flow flagged as "started but not complete" at the end of Session 5 is actually **fully implemented**: `cacheKey`/`normalizeQuery`, the get-then-set flow against `StringRedisTemplate`, 24-hour TTL, JSON (de)serialization via `ObjectMapper`, and `cacheHits`/`creditsSpent` accounting are all present and working in `SearchService.search()`. Session 5's "not done yet" note about this was stale.
- Exported that full explanation as a PDF (`~/Downloads/retrieval-service-explainer.pdf`, 5 pages) at the user's request. Built it as styled HTML first, then discovered macOS's `textutil -convert` does **not** support `pdf` as an output format (only txt/rtf/rtfd/html/doc/docx/odt/wordml/webarchive) — used headless Google Chrome's `--print-to-pdf` instead, which is already installed and needed no new dependency.

**Not done yet:**
- `/api/v1/extract`'s real body — still blocked on the Python extractor sidecar (not yet built).
- `/api/v1/quota` — still hardcoded `1000`, not backed by a real counter.
- `freshness` field on `SearchRequest` — accepted but unused in `SearchService`.
- Nothing in this session touched code; `retrieval-service` is functionally exactly where Session 5 left it (SearXNG search + tiering + Redis cache-aside all working).

**Next session starts with:** either wiring `/api/v1/quota` to a real counter, starting the Python extractor sidecar for `/api/v1/extract`, or moving on to `docs/PLAN.md`'s next scoped item for `retrieval-service`/Week 1.

### Session 7 (2026-08-22) — status audit against PLAN, cache-key correctness fix
**Done:**
- Full honest audit of Week 1 Session 2 against `docs/PLAN.md`. Real state: `/search` done (SearXNG + tiering + Redis cache-aside), `/extract` and `/quota` still stubs, source tiering done, 24h search TTL done. **Not** done: PLAN's `sha256`-based cache-key format, URL normalization, extract 7d TTL, 200KB doc cap, Redis `maxmemory-policy allkeys-lru`.
- Corrected an earlier bad read of my own: query normalization was marked "partial" for `keep quotes`, but `trim().toLowerCase().replaceAll("\\s+"," ")` never touches quote characters — that requirement was already satisfied. Marked ✅.
- **Found a latent correctness bug in the cache key.** `cacheKey(query)` was built from the query text alone, ignoring `freshness`. Since `freshness` maps to SearXNG's `time_range` and *would* change the upstream response, two searches with the same words but different freshness would collide on one cache entry and serve each other's results. Currently latent only because `freshness` isn't wired through to SearXNG yet — it goes live the moment anyone wires it. Fix ordering therefore matters: **fix the label first, wire `freshness` second.**
- Grepped both `docs/PLAN.md` and `CLAUDE.md` for `freshness`/`time_range` at the user's prompting: it appears *only* in PLAN's cache-key formula (line 38) and in Session 6's "accepted but unused" note. There is **no planned build step** for making `freshness` functional. Conclusion: PLAN wants freshness *in the key*, not implemented as a feature — so wiring `time_range` through to SearXNG would be inventing unplanned work. Scope corrected to the key only.
- **Recorded a deliberate deviation from PLAN's cache-key spec** in `docs/PLAN.md`: dropped `maxResults` and `minTier` from `sha256(...)`. Reason: this implementation caches the **raw SearXNG results before tiering/filtering** (`map`/`filter`/`limit` all run after the cache read), so neither field can change the upstream bytes — SearXNG ignores `maxResults` (confirmed against the live container) and has no concept of tiers. Including them would fetch byte-identical data twice and burn a second credit. PLAN's original formula only made sense if the *final filtered list* were cached; ours is the better shape, so the spec was updated rather than silently diverged from.

**Decided (design):**
- Final key shape: `search:v1:{provider}:{sha256(normQuery|freshness)[0:16]}`, provider hardcoded `searxng` for now so a second provider later doesn't force a cache-wide version bump.
- Two separate concerns, deliberately split: (a) **correctness** — the label must include everything that changes the upstream response; (b) **tidiness** — hashing, which only exists to give fixed-length, space-free, colon-free keys. Hashing is *not* what fixes the bug.

**Not done yet:**
- The actual edits: `cacheKey(SearchRequest, String)` signature change, `|`-separated `freshness` (null-guarded to `""`), the `sha256Hex` helper via `MessageDigest` + `HexFormat` (JDK-only, no new dependency), and the call-site update in `search()`.
- `/api/v1/extract`, `/api/v1/quota` — both still stubs.
- URL normalization, extract 7d TTL, 200KB cap, Redis `allkeys-lru` (all PLAN Session 2 items).

**Next session starts with:** making the four cache-key edits in `SearchService`, running `mvn -pl retrieval-service -am test`, and verifying two identical `/api/v1/search` calls report `creditsSpent: 0, cacheHits: 1` on the second.

### Session 8 (2026-08-22) — cache-key fix landed + real quota counter, Week 1 nearly closed
**Done:**
- **All four Session 7 cache-key edits are in `SearchService`** (they were sitting uncommitted in the tree): `cacheKey(SearchRequest, String)` signature change, `|`-separated `freshness` null-guarded to `""`, `sha256Hex` helper via `MessageDigest` + `HexFormat` (JDK-only), and the call-site update in `search()`. Key shape matches the Session 7 deviation spec: `search:v1:searxng:{sha256(normQuery|freshness)[0:16]}`.
- **`/api/v1/quota` is no longer a stub** — this closes a "not done yet" that had been open since Session 6:
  - `QuotaProperties` record (`@ConfigurationProperties(prefix = "quota")`, field `dailyLimit`) per the records-over-`@Value` convention.
  - `QuotaService`: Redis `INCR` on a daily key with a 24h `EXPIRE`; `remaining()` = `dailyLimit - spent`, floored at 0. `SearchService.search()` calls `recordSpend()` only on cache misses (cache hits are free — matches the PLAN's "2nd identical query costs 0 credits" done-when).
  - Controller delegates `/quota` to `quotaService.remaining()`; `application.yml` gained `quota.daily-limit: 1000`.
- `docs/ARCHITECTURE.md` created (~211 lines) — first architecture doc for the repo.
- `mvn -pl retrieval-service -am test`: green, 6/6 (`SourceTierResolverTest` 5 + contextLoads 1).

**Not verified / not done yet:**
- The live two-call check from Session 7's exit criteria (`creditsSpent: 0, cacheHits: 1` on second identical call) has **not** been run against docker-compose yet — code is in place but unverified end-to-end.
- Nothing here is committed — Sessions 5–7's work plus all of today is one uncommitted working tree.
- `/api/v1/extract` still a stub (blocked on Python extractor sidecar).
- URL normalization for extract keys, extract 7d TTL, 200KB doc cap, Redis `allkeys-lru` (PLAN Session 2 items).
- `freshness` deliberately not wired to SearXNG `time_range` — out of scope per Session 7's decision.

**Minor observation (not fixed, user's call):** `QuotaService.key()` returns `"quota:v1" + LocalDate.now()` → `quota:v12026-08-22`. Missing separator; works fine but `quota:v1:2026-08-22` would be consistent with the search key style. One-character fix whenever convenient.

**Verified at session close (2026-08-22):** re-ran `mvn -pl retrieval-service -am test` — BUILD SUCCESS, `Tests run: 6, Failures: 0, Errors: 0` (5 `SourceTierResolverTest` + 1 `contextLoads`). Confirmed the four cache-key edits and all quota wiring are present in the working tree exactly as described above. **Docker daemon is not running**, so the live two-call cache-hit check still cannot be performed — that is the blocker on the one remaining unverified item, not a code problem.

**Next session starts with:** start Docker Desktop → `docker compose up -d` → run the two-call verification (`creditsSpent: 0, cacheHits: 1` on the second identical `/api/v1/search`) → commit the tree (Sessions 5–8 as logical chunks) → then start the Python extractor sidecar for `/api/v1/extract`.

### Session 9 (2026-08-22) — hex handoff artifact + ARCHITECTURE.md diagram rebuild
**Done:**
- Re-verified Session 8's claims by actually running `mvn -pl retrieval-service -am test` again (BUILD SUCCESS, 6/6) and reading the code directly rather than trusting the log — confirmed accurate, then appended the verification note and the Docker-daemon-down blocker to the Session 8 entry above.
- Built a machine-readable session handoff at the user's request, for another agent to pick up context. Used **hex encoding, not a hash** — a hash (e.g. SHA-256) is one-way and cannot be decoded back into the summary, so it would carry zero information to a reading agent; hex is reversible. Wrote `docs/SESSION_HANDOFF.hex` (14,354 hex chars / 7,177-byte plaintext payload covering project scope, the working agreement, Session 7–8 reasoning and code state, verified-vs-not, known issues, uncommitted files, and next-session order) plus `docs/SESSION_HANDOFF.md` with decode instructions (`xxd -r -p` or a Python one-liner). Round-trip verified byte-identical via `diff` before writing.
- **Rebuilt the `docs/ARCHITECTURE.md` flow diagram from scratch**, programmatically (Python box-drawing generator) rather than hand-aligning ASCII, because the prior diagram (built by another session) had border misalignment. New diagram covers the **entire target system**, not just what's built — Browser → control-plane (①submit/⑩SSE) → Redpanda (②fan-out/⑥fan-in) → agent-service's three profiles (RESEARCHER×6 steps 1-8, WRITER, CRITIC) → retrieval-service (④ the one mandatory web chokepoint) → Redis/SearXNG/Extractor side-by-side → Critic loop math (⑦-⑨) → Postgres/Anthropic API side-by-side at the bottom. Every box/line tagged `(B)`/`(P)`; `retrieval-service`'s header uses `(B/P mixed)` since it mixes done (`/search`, `/quota`) and stubbed (`/extract`) endpoints per-line inside the box. Spliced in place of the old diagram; the rest of the doc (lifecycle steps, component table, DB schema, retrieval-service internals) was left untouched since it was still accurate.

**Not done yet:** same as Session 8's exit list — Docker still not started this session, so the live two-call verification remains unperformed. Nothing in the actual `retrieval-service` code changed this session; only documentation/handoff artifacts were produced.

**Next session starts with:** unchanged from Session 8 — start Docker Desktop → `docker compose up -d` → run the two-call cache-hit verification → commit the tree → then the Python extractor sidecar. To resume with full context, either use `/resume` (harness-native, no action needed) or point a fresh agent at `docs/SESSION_HANDOFF.hex` and decode it per `docs/SESSION_HANDOFF.md`.
