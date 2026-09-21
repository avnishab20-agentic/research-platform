# Research Platform

Multi-agent research system: a question is decomposed into sub-questions, researched
in parallel over Kafka, synthesized into a report, then **verified claim-by-claim**
against the sources it cites.

The verification is the point of the project. Never cut it.

Guardrails and evals are part of that, not polish. Cut UI before you cut either.

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

**Guardrails are configuration, not code paths.** Every limit — call budgets, timeouts,
document caps, fan-out caps, unsupported-ratio thresholds, eval pass marks — is a number
in one `guardrails:` tree bound to a single record in `common/`. Never a hardcoded
constant, never a scattered `@Value`. Tuning is a config edit plus a restart.

**Guardrails stay small on purpose (~6 hrs across weeks 1–4).** If a guardrail needs its
own subsystem, it isn't in scope: circuit breakers, dollar-level cost accounting,
prompt-injection scanning and retrieval-quality scoring are listed under *Deliberately
not built* in `docs/PLAN.md` so they don't creep back in. One global
`guardrails.mode: ENFORCE | SHADOW` switch, not per-guardrail modes.

**Cost is bounded by call count, not dollars.** `max-searches` and `max-llm-calls` per run
use integers already being incremented. Dollar accounting needs per-call token
aggregation for a number that only has to be approximately right.

**A failed run is published, never silently dropped.** Ceiling breached, deadline blown,
unsupported ratio too high — the result is a PARTIAL or `UNVERIFIED`-banner report.
Suppressing a bad report hides the exact behaviour the project exists to expose.

**Evals run on fixture mode and cost $0.** Three of them, replayed from recorded JSON.
An eval you can't afford to run is an eval you don't have.

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
the Critic loop, cache key normalization, all agent prompts, and the guardrail
*decision* logic (what counts as a breach, what happens on one).

Guardrails split the same way as everything else: **you** write the
`@ConfigurationProperties` record, the YAML tree, the eval harness and the fixture
replay; **I** write the enforcement points that read them.

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

### Session 10 (2026-08-23) — cache verification closed, extractor sidecar built, Java client seam
**Done:**
- **The two-call cache verification finally ran** — open since Session 7, blocked every session since by Docker being down. Started Docker, `docker compose up -d`, ran the same `/api/v1/search` twice: 1st `creditsSpent:1, cacheHits:0`, 2nd `creditsSpent:0, cacheHits:1`, and `/api/v1/quota` reported `999`. That proves three things at once: the cache key is stable across identical requests, the cache-aside flow actually reads Redis, and `recordSpend()` fires on the miss but **not** on the hit. **This is PLAN's Week 1 "done when" for caching — met.**
- Committed the Sessions 5–9 backlog as `ae3fbc3 Session 5-9` (was one large uncommitted tree).
- **Extractor sidecar built** (Claude's column per the working agreement) — `extractor/requirements.txt`, `extractor/main.py` (FastAPI + trafilatura, ~40 lines), `extractor/Dockerfile` (`python:3.12-slim`, requirements copied before `main.py` so pip re-runs only when deps change, `--host 0.0.0.0` so the port mapping actually reaches it). Returns only `OK` or `PAYWALLED` — it never fetches URLs, so `UNREACHABLE`/`ROBOTS_DENIED`/`TOO_LARGE` are Java's job at fetch time.
- `extractor` service added to `docker-compose.yml` — the first service built from our own `build: ./extractor` recipe rather than a pre-made image; healthcheck uses plain `python -c urllib.request` because the slim image has no `curl`. First attempt failed with `additional properties 'extractor' not allowed` — the block was pasted at column 0, making it a sibling of `services:` instead of a child. Fixed by indenting 2 spaces.
- Sidecar verified live: good-page HTML → `status:"OK"` with the `<nav>Home Menu Login</nav>` stripped out of `text`; paywall HTML → `status:"PAYWALLED"`.
- **Java client seam written by the user** (guided, several correction rounds): `extract/ExtractorRequest`, `extract/ExtractorResult`, `config/ExtractClientConfig` (bean `extractorRestClient` from `extractor.base-url`), `extract/ExtractorClient` (`POST /extract` via `RestClient`), and `extractor.base-url: http://localhost:8000` in `application.yml`.
- **The two-bean `@Qualifier` trap hit exactly as predicted.** Adding a second `RestClient` bean retroactively breaks the *existing* injection point in `SearchService` — Spring could previously pick by type alone. Both `SearchService` (`@Qualifier("searxngRestClient")`) and `ExtractorClient` (`@Qualifier("extractorRestClient")`) now qualify **on the constructor parameter**, not on the constructor or the class (`@Qualifier` isn't a legal annotation on a constructor declaration).
- `mvn -pl retrieval-service -am test` → **BUILD SUCCESS, 6/6**. `contextLoads` passing is the real proof here: it boots the full context, so both `RestClient` beans were created and correctly disambiguated.
- Redis eviction configured (PLAN Session 2): `command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru` on the `redis` service. Default is `noeviction`, which **rejects writes** at the ceiling rather than making room — wrong for a cache, where every entry is disposable and re-fetchable. Editing compose wasn't enough: a container's startup command is fixed at creation time, so the live container still reported `noeviction` until `docker compose up -d redis` recreated it (same shape as Session 3's stale-JVM trap). Verified live: `allkeys-lru`, `maxmemory 268435456`.
- `QuotaService.key()` colon fix landed — now `quota:v1:2026-08-23`, consistent with the search key style. Side effect: the old key is orphaned so today's counter restarts at 0 (harmless, it's a 24h-TTL counter).

**Correction rounds worth remembering (all user-typed Java):** `class` used where `record` was required (third time this trap has appeared); `PublishedAt`/`Status` capitalized — would have compiled fine and silently yielded `null` forever, since Jackson matches JSON keys to record components case-sensitively; a method declared *outside* the class's closing brace; `public class Foo ()` with a record-style parameter list; `.url()` instead of `.uri()` on `RestClient`; `.baseUrl()` called with no argument while the `@Value` parameter went unused; `ExtractorClient` initially created in `config/` instead of `extract/` (it's a worker, not configuration — `ExtractClientConfig` *builds* the client, `ExtractorClient` *uses* it).

**Deliberately not wired:** `POST /api/v1/extract` is still a stub. `ExtractorClient` exists and compiles but nothing calls it. Fetching pages, mapping `ExtractorResult` → `dto.Document`, and the extract cache-aside are the next chunk.

**Not done yet (PLAN Week 1 Session 2/4 remainder):**
- URL normalization (lowercase host, strip fragment, strip `utm_*`/`fbclid`/`gclid`/`ref`, strip trailing slash, keep + sort other params) — **user's task**, it's cache key normalization
- `extract:v1:{sha256(normUrl)[0:16]}` cache-aside, 7-day TTL, 200KB doc cap
- All of PLAN Session 4 (Redis Lua token bucket, `CompletableFuture` parallel fetch, single-flight/thundering-herd lock, `Semaphore(4)`) — explicitly to be hand-written, no library shortcuts

**Later in the same session — URL normalization + extract cache key (PLAN Session 2, two more items closed):**
- `UrlNormalizerTest` written first as the spec (8 tests, one per rule), deliberately red before any implementation existed. Two rules were flagged as judgement calls rather than smuggled in: lowercasing the **scheme** as well as the host, and stripping a leading `www.` (consistent with what `SourceTierResolver` already does).
- `url/UrlNormalizer` — `normalize(String)`: parse via `java.net.URI` (never regex), lowercase scheme, lowercase host + drop `www.`, drop trailing `/`, clean the query, reassemble. The fragment vanishes for free because `getFragment()` is simply never read. Noise params are two named constants: prefix `utm_`, plus exact `fbclid`/`gclid`/`ref`. Filtering happens on the **param name only** (`paramName()` splits at the first `=`) so a legitimate value like `?q=gclid` isn't thrown away. `getQuery()` returns **null**, not empty string, when there's no `?` — guarded.
- Written by Claude at the user's explicit request after two rounds of the user's own attempts. **Recurring Java traps seen again in those attempts:** the method declared *outside* the class braces (which produces the very confusing `unnamed classes are a preview feature` error — Java assumes you meant the Java 21 preview bare-method feature), statements written in reverse dependency order (`uri` used on four lines before the line that creates it — in a method body, order *is* execution, unlike class fields), `String uri = URI.create(...)` type mismatch, and `toLowercase()` for `toLowerCase()`.
- `hash/Hashing` — `sha256Hex` lifted out of `SearchService` into a shared static utility now that two call sites need it; `SearchService` calls `Hashing.sha256Hex(...)` and lost its four crypto imports. Moved rather than copy-pasted so the two can't drift.
- `extract/ExtractCacheKey.of(url)` → `extract:v1:{sha256Hex(normalize(url))[0:16]}`, prefix as a named constant so a version bump is a one-line invalidation.
- `ExtractCacheKeyTest` (3 tests). The load-bearing one asserts `https://www.TheHindu.com/news/rbi/?utm_source=twitter#top` and `https://thehindu.com/news/rbi` produce the **same** key — four spellings of one article now cost one fetch. The third test guards the other direction: `?page=1` and `?page=2` must stay different, so nobody later "optimises" the normalizer into over-stripping.
- `mvn -pl retrieval-service -am test` → **BUILD SUCCESS, 17/17** (1 contextLoads + 5 tier + 8 url + 3 extract-key).

**PLAN Week 1 Session 3 (extractor sidecar) is complete, with one recorded deviation:** PLAN says the sidecar returns `OK | PAYWALLED | ROBOTS_DENIED | UNREACHABLE | TOO_LARGE`. Ours returns only `OK | PAYWALLED`, deliberately — the sidecar never fetches URLs, so it cannot observe a network failure, a `robots.txt` rule, or an oversized response. Those three are decided by Java at fetch time and set on `Document.status` there. Same enum overall, different owner for three of its values.

**Not done — the single remaining PLAN Session 2 item:** wire `POST /api/v1/extract` end to end. That means fetching the HTML in Java, the cache-aside around `ExtractCacheKey` with a **7-day** TTL, the **200KB** document cap, calling `ExtractorClient`, mapping `ExtractorResult` → `dto.Document` with `tier` from `SourceTierResolver`, and deciding `UNREACHABLE`/`ROBOTS_DENIED`/`TOO_LARGE` at fetch time. `/api/v1/extract` is still a stub returning `List.of()`.

**Later in the same session — `docs/story/` narrative guide generated (uncommitted, untracked):**
- Built a 10-file narrative guide "The Story of This Codebase" under `docs/story/` (~1,727 lines total) from a full read of every source file, generated against commit `6955d9f`. Every factual claim carries a `file:line` citation; anything untraceable is marked `COULDN'T TRACE` rather than invented.
- Structure: `00-the-world` (problem, stack+library breakdown with what-breaks-if-deleted, full cast of classes as characters by layer), `01-search-flow`, `02-quota-flow`, `03-extract-stub-flow`, `04-sidecar-extraction-flow`, `05-boot-and-compose` (six numbered flow narrations, each with trigger/diagram/narration/framework-magic/unhappy-paths/if-I-changed-X), `90-method-reference` (every non-trivial Java + Python method with CB/CO/SE/FM columns), `91-glossary` (terms, annotations, patterns; 🧱 marks blueprint-only), `92-check-yourself` (15 questions incl. trace-the-path, answers collapsed), plus a `README.md` TOC.
- Scope discipline: flows 1–6 narrate only real running code; the agent pipeline (planner → Kafka fan-out → RESEARCHER/WRITER/CRITIC → verified report → SSE) is pointed at as blueprint without inventing unbuilt code.
- The guide's `00-the-world` also surfaces three Suspicious findings worth a look: root `pom.xml`'s `<java.version>` still not wired into agent-service/control-plane compilers (the "-source 8" trap waiting to recur on their first `record`), `common/README.md` claims dependencies no pom actually declares, and `QuotaService` has a dead `@Configuration` import while the quota remains a fuel gauge not a fuel cutoff.
- **Not committed** — `docs/story/` is untracked in the working tree; the rest of the tree is clean on top of `6955d9f`.

**Next session starts with:** that `/extract` wiring (one coherent chunk — it needs real HTTP error handling, which is where the fiddly cases live). Closing it completes PLAN Week 1 Session 2. Then only PLAN Session 4's hand-written concurrency set (Redis Lua token bucket, `CompletableFuture` parallel fetch, single-flight/thundering-herd lock, `Semaphore(4)`) stands between here and Week 2. The untracked `docs/story/` guide is ready to commit whenever convenient (pure docs, no code impact).

### Session 11 (2026-08-29) — `/extract` wired end to end, `TokenBucket` built, session method changed
**Done:**
- **`POST /api/v1/extract` is no longer a stub — PLAN Week 1 Session 2 is now closed**, after being open since 2026-08-13 across six sessions.
  - `extract/PageFetcher` (Claude, after the user's start): fetches one URL, **never throws** — every failure comes back as a `FetchedPage` status so one dead URL can't sink a batch. Uses `.exchange()` rather than `.retrieve()` because a 404 is a *result* (`UNREACHABLE`), not an exception to unwind for. Two size guards: `Content-Length` checked before reading a byte, then `readNBytes(maxBytes + 1)` while reading — asking for one byte over the limit proves you're over it while never buffering more than 200KB+1, which closes the heap hole a plain `.body(String.class)` would leave. `.uri(URI.create(url))` not `.uri(url)`, because a String is treated as a URI *template* and a real URL containing `{` would blow up during expansion.
  - `extract/ExtractService` (Claude): cache-aside over `ExtractCacheKey`, 7-day TTL, `ExtractorClient` call, `SourceTierResolver` for tier, `tools.jackson` ObjectMapper to match `SearchService`.
  - `RetrievalController` delegates `/extract`; `ExtractClientConfig` switched from a stray `@Value` to the `ExtractProperties` record, per the records-over-`@Value` convention.
  - `PageFetcherTest` (Claude): 6 tests via `MockRestServiceServer` bound to a `RestClient.Builder` — no Spring context needed, since the class takes its client as a constructor arg. **Suite: 23/23 green** (was 17).
- **Two design decisions recorded:** (a) `UNREACHABLE`/`TOO_LARGE` are **never cached** — a timeout is a fact about this moment, not about the page, and caching it would blind us for 7 days; `OK` and `PAYWALLED` both cache, since both are stable facts about the page's content. (b) A dead sidecar becomes `UNREACHABLE` per-URL rather than 500-ing the whole batch — slightly dishonest (the *page* was reachable, our sidecar wasn't) but the enum has no better value and inventing one is scope creep.
- **`ratelimit/TokenBucket` written by the user, guided** (PLAN Session 4, first of four concurrency pieces). Per-domain, capacity 3, 1 token/sec, no background timer — each call converts elapsed time into tokens. Three lines carry the decisions: `elapsedMillis / 1000.0` (the `.0` is load-bearing — integer division floors every sub-second gap to zero and the bucket never refills under steady traffic, which is also why `tokens` is a `double`); `lastRefillMillis = now` **before** the guard (an NTP jump backwards would otherwise freeze the bucket until real time caught up); `Math.min(capacity, ...)` (an idle domain must not bank 28,800 tokens overnight). `synchronized` because refill→check→decrement is three steps and two researchers on the same domain can interleave — the identical race that forces the Redis port to be a Lua script rather than GET-check-SET.
- **The clock is a constructor-injected `LongSupplier`, not `System.currentTimeMillis()` inside the class.** This is the one decision that makes the class testable: a test moves time nine seconds forward in one instant line instead of `Thread.sleep(9000)`.
- **Corrections the user worked through on `TokenBucket`** (worth remembering as the shape of the learning loop): `tokens = capacity - lastRefillMillis` (a token count minus a timestamp — "3 apples minus Tuesday"); a `final` field assigned both at its declaration *and* in the constructor (allowed exactly once, either place, never both); a `long lastRefillMillis` constructor parameter instead of the `LongSupplier` — **a photograph of a clock, not a clock**, frozen at construction and unable to answer "what time is it now"; `refill()` returning a number instead of mutating `tokens`; `tryAcquire` returning `true` without spending anything; and `tokens > 0` instead of `>= 1.0` (0.3 of a token isn't enough to make a whole request). The user got decision #1 (a fresh domain's bucket starts **full**, since nobody has been rate-limited by it yet) and the `double` for `tokens` unprompted, and line 23's shape — `now - lastRefill`, scaled by the rate — was structurally correct.
- **Honest status audit run against `docs/PLAN.md`.** Week 1 ~80% (Sessions 1-3 done, Session 4 ~35%: bucket done; `DomainRateLimiter`, Lua port, `CompletableFuture` fan-out, single-flight lock and `Semaphore(4)` remain). Weeks 2-4 at 0%. Guardrails **~3%** of the 7-item / 6-hour table — only item 3 is partial (fetch timeouts ✅, scheme allowlist and private-network block ❌); items 1, 2, 4, 5, 6, 7 untouched. (An earlier figure of ~10% in this session was wrong: it leaked in the 200KB cap, `TokenBucket` and the TTLs, which PLAN explicitly counts as zero-cost extras *outside* the 6 hours.) Note also that none of the limits built so far read from a `guardrails:` tree — they sit in `ExtractProperties`/`RateLimitProperties`, so guardrail item 1 is a small refactor of what exists, not purely additive. **Quota is still a gauge, not a cutoff**: `remaining()` reports the number but nothing checks it before spending, so a run can burn past 1000 with no symptom but the gauge sitting at 0 (guardrail item 2).
- **Corrected my own estimate mid-session.** First scored ~60 plan-hours remaining against the user's schedule; that was the wrong denominator, since the SSE page, fixture replay, eval harness, DTOs, Flyway DDL and tests are all Claude's column per the working agreement. Of ~60 hours left, **~38 are actually the user's**, and the 2x learning multiplier applies only to those. That column split is right, but the **schedule figure derived from it was too rosy**. A momentum check at session close (git: first commit 2026-08-08, 11 sessions, 3.35 of 4 Week-1 PLAN sessions done in 21 days ≈ **5.7 delivered plan-hours/week**) projects the remaining ~60 plan-hours at **~10 weeks → mid-November**, roughly 3 weeks past the end-October target. A second method agrees: 38 user-hours × 2 multiplier ÷ 10 hrs/week = 7.6 weeks, plus ~2 for Claude's 22 hours of shared session time. **Believe the observed rate over the stated one.** The user's 10 hrs/week estimate is accurate — 10 real hours simply buys ~5 plan-hours at learning pace. Biggest lever is not more hours but **fewer zero-code sessions**: 2 of 11 (Sessions 6 and 9) produced only docs/handoff artifacts, ~18% of calendar. The remaining 38 are the concentrated hard parts by design (fan-in, Critic loop, concurrency primitives); there's no easy filler left to coast through.
- **Biggest schedule risk named:** fixture mode sits in Week 2 and all three evals depend on it. Build it the day the first researcher works, not deferred as "test infrastructure" — CLAUDE.md's own rule is to cut UI before evals.

**The method changed, and this is the durable outcome of the session.**
`PageFetcher` was dictated line-by-line while the user transcribed; it produced working code and a demoralised user (*"I am just writing whatever you say... I am sure i wouldnt be able to wire even one class myself"*). `TokenBucket` was run the other way — spec plus five decisions to reason about, the user writing it wrong twice, one correction at a time with the reasoning — and produced *"i almost got that logic myself, this was the best coding session of this entire project."* Same person, same day, similar difficulty. **From now on: spec and decisions, never dictation; correct one issue per round with the why; only write the class outright when explicitly asked.** The related split to respect is that the user stalls on unfamiliar **library APIs** and is fine on **pure logic** — which is exactly the line the working agreement already draws.

**Not done / not verified:**
- **Docker was down all session**, so `POST /api/v1/extract` has never been run live against the real trafilatura sidecar. The fetch branches are covered by mocks; the actual round-trip is untested.
- `RateLimitProperties` record + the `rate-limit:` YAML block, and `DomainRateLimiter` (a `ConcurrentHashMap<String, TokenBucket>` with `computeIfAbsent`, plus host extraction that makes `www.thehindu.com` and `thehindu.com` share one bucket) — both the user's, both small.
- `TokenBucket` has **no tests yet** (Claude's column, queued next).
- `TokenBucket` is deliberately **not wired into the fetch path** — where it gets called is an enforcement decision, and enforcement points are the user's column.
- `CLAUDE.md`/`docs/PLAN.md` carry uncommitted guardrails/evals edits from an earlier session; `docs/story/` (10 files) is untracked and worth committing; `graphify-out/` (89 files) is generated output and should be **gitignored, not committed**.

**Next session starts with:** `RateLimitProperties` + YAML, then `DomainRateLimiter`, then Claude writes the `TokenBucket` tests (a fake clock makes the whole class testable in milliseconds — the payoff for the injected `LongSupplier`). That leaves the Lua port, `CompletableFuture` fan-out, single-flight lock and `Semaphore(4)` to close Week 1. Live-verify `/extract` whenever Docker is next up.

### Session 12 (2026-08-30) — `TokenBucket` tests, `UrlNormalizer.host()` extracted, `DomainRateLimiter` done
**Done:**
- **Found the tree had not compiled since Session 11.** `SearxngClientConfig` had `.baseUrl(baseUrlf)` — a stray `f` — **committed on `main`**. One-character fix. Lesson worth keeping: run `mvn test` before committing; a broken build sitting in history is expensive to stumble into later.
- **`TokenBucketTest` written (Claude's column), 7 tests**, each pinning one decision the user reasoned through when writing the class: starts full, refuses when drained, one token per second, `>= 1.0` not `> 0`, sub-second gaps accumulate (goes red the moment anyone writes `/ 1000` instead of `/ 1000.0`), `Math.min` idle cap, and backwards-clock resync. Uses a hand-driven `FakeClock` inner class — eight simulated hours run in microseconds, which is the whole payoff for `TokenBucket` taking an injected `LongSupplier`.
- **`RateLimitProperties` + `rate-limit:` YAML block** (user). Two corrections: `@Configuration(prefix=...)` used instead of `@ConfigurationProperties` (different annotations — one means "look inside for `@Bean` methods", the other means "fill these fields from YAML"; `@Configuration` has no `prefix` attribute so it did not compile), and the field types were inverted. Landed on **`int capacity, double refillRate`**: capacity is a count of whole requests, while a rate is naturally fractional — `refill-rate: 0.5` (one call every two seconds, for a fragile government site) truncates to `0` under `int` and the bucket then **never refills**. `int` widens to `double` free at the `TokenBucket` call site, so it costs nothing.
- **`UrlNormalizer.host(String)` extracted** (user, guided). The host rule — get host, lowercase, strip `www.` — existed in `UrlNormalizer.normalize()` *and* `SourceTierResolver`, and `DomainRateLimiter` needed it a third time. Now one copy; `normalize()` delegates to it and throws `IllegalArgumentException` (naming the offending URL) where it previously died on an opaque NPE. Pure move, verified by the 8 existing `UrlNormalizerTest` tests staying green.
  - **Decision: `host()` returns `null` for a hostless URL rather than throwing.** A low-level utility reports the fact; each caller picks the consequence. Matches what `SourceTierResolver` already does.
  - Corrections along the way, all recurring traps: **method declared outside the class braces** (4th appearance — produces the misleading `implicitly declared classes are a preview feature` error, because Java assumes you meant the Java 21 bare-method preview; **whenever an error about a method says "preview", check the braces first**); the brace fix then swallowed `normalize()`'s closing brace, nesting the three private methods *inside* it; missing return type on the method declaration; and **a null guard placed after the line it was meant to protect** (`getHost().toLowerCase()` already NPEs before the check runs). Also the guard initially tested `uri == null` — but `URI.create()` never returns null, it throws; the quiet-null case is `getHost()` on a valid-but-hostless URI like `mailto:` or `about:blank`.
  - Useful side-lesson: **IntelliJ's auto-indent is a picture of the brace structure.** Untouched code suddenly shifting right means a brace went missing above it; code flat against the left margin has escaped its class. Cmd+Alt+L after writing a method surfaces both instantly.
- **`DomainRateLimiter` done** — `ConcurrentHashMap<String, TokenBucket>`, `computeIfAbsent`, keyed on `UrlNormalizer.host(url)`.
  - **Decision: a null host returns `false`, it does not throw.** `ConcurrentHashMap` rejects null keys outright, and `PageFetcher` was deliberately built so one bad URL can't sink a batch — throwing here would undo that.
  - **Decision: the map is never pruned.** A run touches a few hundred domains, a bucket is tens of bytes. Comment records the tradeoff so a later reader knows it was a choice; no eviction built.
  - **`DomainRateLimiterTest` (Claude), 5 tests.** The load-bearing one fetches three differently-spelled URLs on one site (different paths, `www.`, uppercase scheme) and asserts the fourth is refused — it goes red the instant anyone swaps `host()` back to `normalize()`.
- **Suite: 23 → 35 green.**

**Later in the same session — the untested half of `retrieval-service` covered (Claude's column), 35 → 67 green:**
- Before this, four classes carrying real logic had **no tests at all**: `SearchService`, `ExtractService`, `QuotaService`, `ExtractorClient`. All four now have them.
- **`SearchServiceTest` (8)** — SearXNG faked with `MockRestServiceServer`, Redis with Mockito, `SourceTierResolver`/`ObjectMapper` real (they're pure logic; faking them would only test the fakes). Covers: cache miss spends 1 credit + writes with a 24h TTL + calls `recordSpend()`; **cache hit costs 0, skips upstream entirely and never records a spend** (this is PLAN's "2nd identical query costs 0 credits" as an assertion); tier filtering; `maxResults` capped client-side; SearXNG `content` → our `snippet`; query normalization collapsing case/whitespace to one key; **`freshness` producing distinct keys**; and the key shape `search:v1:searxng:` + 16 chars.
- **`ExtractServiceTest` (8)** — the interesting axis is *what gets cached*, so most tests assert on whether Redis was written to at all. `OK` and `PAYWALLED` cache with the 7d TTL; `UNREACHABLE`, `TOO_LARGE` and a **dead sidecar** do not. Plus one bad URL not sinking a batch, and four spellings of one article sharing a single cache entry.
- **`QuotaServiceTest` (5)** — full limit before any spend, subtraction, **floors at 0** (quota is still a gauge, not a cutoff, so overspend is reachable), `INCR` + 24h `EXPIRE`, and the `quota:v1:<date>` key shape.
- **`ExtractorClientTest` (2)** — the class is five branchless lines, so what these actually guard is the **JSON contract with the Python sidecar**: field names on both sides, which no compiler checks. Directly targets the Session 10 trap where a record component named `Status`/`PublishedAt` compiles fine and stays `null` forever.
- **`HashingTest` (4)** and **5 more in `UrlNormalizerTest`** covering `host()` directly (lowercase + `www.` strip, path/query/fragment ignored, non-`www.` subdomains kept, `null` for hostless URIs, and `normalize()` throwing a message that names the offending URL).
- **A real thing surfaced by writing these:** query normalization applies to the **cache key only** — the raw query as typed goes upstream to SearXNG. Correct as designed (normalization exists to make keys collide, not to rewrite the search), but it wasn't written down anywhere, and a test asserting the normalized form on the wire fails. Now documented in the test.
- Redis is mocked rather than run via Testcontainers, deliberately: every branch tested here is arithmetic, key-shaping and control flow, none of which depends on Redis behaving like Redis. **The real INCR/EXPIRE and TTL round trips still need an integration test** — that gap is unclosed and is a fair criticism of this suite.

**A wrong steer from Claude, corrected in-session:** I said keeping exactly one constructor `public` would let Spring disambiguate a constructor pair. **That is false.** Spring's rule is *exactly one constructor total* → it uses that one; *more than one* → it needs `@Autowired` on the intended one, otherwise it falls back to hunting a no-arg constructor and fails with `No default constructor found`. Visibility is irrelevant. Cost a red build that wasn't the user's mistake. `@Autowired` is now on the public single-arg constructor with a comment saying it is required, not decorative.

**Near-miss worth remembering:** `tryAcquire` was first written calling `normalize()` instead of `host()`. That would have keyed each bucket by the *full URL*, so every article on a site would mint its own fresh full bucket and the limiter would return `true` forever — a rate limiter that compiles, runs, has tests, and limits nothing. Caught before it landed; `everyUrlOnOneDomainDrawsFromTheSameBucket` now guards it permanently.

**Session-method note:** the correction loop ran ~7 rounds on `DomainRateLimiter` and the last few were transcription noise (a package imported instead of a class, `throws` for `throw`, `.rate.` where a comma belonged), ending in *"ugh whatever just show me the class code."* **Once the remaining issues are typos rather than decisions, the loop has stopped teaching — write the class then, without waiting to be asked.** Recorded in memory.

**Found, not fixed (user's call):** `SourceTierResolver` strips `www.` **before** lowercasing, so `WWW.TheHindu.com` keeps its uppercase prefix and silently falls through to **tier 3 instead of tier 2**. Real live bug, ~10 min; the fix is to call `UrlNormalizer.host()`, which also deletes the third copy of the host logic.

**Not done / not verified:**
- **Docker down for a third consecutive session** — `POST /api/v1/extract` has still never run against the real trafilatura sidecar, only mocks.
- `DomainRateLimiter` is **deliberately not wired into the fetch path**. Where it gets called is an enforcement decision, and enforcement is the user's column.
- **The in-memory `TokenBucket`/`DomainRateLimiter` do NOT tick PLAN Session 4 item 2**, which asks for the bucket **as a Redis Lua script**. It is groundwork (same algorithm, and the atomicity lesson transfers), but an in-memory bucket is per-JVM: two `retrieval-service` instances would each keep their own buckets and hand a domain double the rate. That is precisely what Week 5's Kubernetes scaling breaks, and why PLAN specified Redis. `synchronized` solves the race inside one process; a Lua script solves it across all of them.

**Honest Week 1 status — Sessions 1-3 done, Session 5 moved to Week 5, so Week 1 is now entirely Session 4, and Session 4 is 1 of 5:**

| Item | State |
|---|---|
| Cache-aside by hand (`RedisTemplate`, Jackson, TTL) | ✅ done (search 24h, extract 7d) |
| Token bucket as a **Redis Lua script** | ❌ not started |
| Parallel fetch via `CompletableFuture` (`allOf`, per-future `exceptionally`) | ❌ not started |
| Single-flight / thundering-herd lock (`SET key val NX PX 30000`) | ❌ not started |
| `Semaphore(4)` in front of the extractor sidecar | ❌ not started |
| Done-when: 20 concurrent identical `/search` → 1 upstream call, 19 cache hits | ❌ not run |

Estimated **~6.5 plan-hours** remaining (Lua ~2h, `CompletableFuture` ~2h, single-flight ~1.5h, semaphore ~0.5h, verification ~0.5h). At the ~5.7 delivered plan-hours/week measured in Session 11, that is **~1 to 1.5 calendar weeks to close Week 1**. All four remaining items are the user's column by the working agreement, and are the concentrated hard part by design — no boilerplate left to coast through.

**Next session starts with:** bring Docker up first and live-verify `/extract` against the real sidecar (three sessions overdue; better to find surprises there before layering concurrency on top). Then the Redis Lua token bucket, then `CompletableFuture` fan-out, single-flight lock and `Semaphore(4)`. The `SourceTierResolver` lowercase-ordering bug is a good 10-minute warm-up.


### Session 13 (2026-09-03) — Lua token bucket, `SourceTierResolver` bug fixed
**Done:**
- **`SourceTierResolver` lowercase-ordering bug fixed** (found and left in Session 12). It stripped `www.` *before* lowercasing, so `WWW.TheHindu.com` kept its uppercase prefix and silently fell through to tier 3 instead of tier 2. Fix was to delegate to `UrlNormalizer.host(url)` — which also deleted the third copy of the host-extraction logic. `SourceTierResolverTest` gained a case pinning it (5 → 6 tests).
- **Token bucket ported to a Redis Lua script** — PLAN Session 4 item 2, the one the in-memory `TokenBucket` explicitly did *not* satisfy. `scripts/token_bucket.lua` holds the same algorithm (capacity, fractional refill, `Math.min` idle cap, `>= 1.0` not `> 0`, backwards-clock clamp), and `DomainRateLimiter` loads it once via `DefaultRedisScript` and calls it by SHA.
  - **Time comes from Redis (`TIME`), not the caller.** Every instance now shares one clock, so a JVM with drift can't corrupt a shared bucket. This is what the injected `LongSupplier` was standing in for.
  - **The whole reason it's Lua:** Redis runs a script start-to-finish with nothing interleaved, so read-check-write is atomic. `synchronized` fixed the race inside one JVM; only the script fixes it across instances — which is exactly what Week 5's Kubernetes scaling would otherwise break.
  - Key `EXPIRE` is `ceil(capacity / rate)`: past that point an untouched bucket has refilled to full, which is identical to a missing key, so the key carries no information and can go.
- `TokenBucket` and `TokenBucketTest` (130 lines, 7 tests) **deleted** — the arithmetic moved into Lua and can no longer be reached from Java. `DomainRateLimiterTest` rewritten to cover what is still Java: which key a URL maps to, and the null-host refusal.
- **Cost of that move, recorded honestly:** the refill/burst/idle-cap behaviour lost its tests. Nothing verified the Lua arithmetic until Session 14.

**Next session starts with:** live-verifying the Lua script and `/extract` (both still unproven against real containers), then `CompletableFuture` fan-out, single-flight lock and `Semaphore(4)`.

### Session 14 (2026-09-05/06) — Docker finally up: two live bugs found, `/extract` closed, rate limiter wired, console built
**The overdue verification finally ran, and it was worth it — both things that had gone unverified were broken.**

- **The Lua token bucket works.** First execution ever against a real Redis: burst of 3 allowed, calls 4 and 5 refused, a 2-second wait refilled exactly 2 tokens, `TTL` = 3 = `ceil(capacity/rate)`. Tested by `docker cp`-ing the script into the container and driving it with `redis-cli --eval`, which needs no app running.
- **`POST /api/v1/extract` had NEVER once succeeded.** Every call returned `UNREACHABLE`. Root cause took a wire capture (`nc -l` + a standalone `RestClient`) to find: **RestClient's default JDK HttpClient offers an HTTP/2 upgrade on plaintext** (`Connection: Upgrade`, `Upgrade: h2c`), and **uvicorn's h11 reads that as a protocol switch and never consumes the request body** — so FastAPI saw `body: null` and 422'd every single call. `PageFetcher` was unaffected only by accident: it uses `SimpleClientHttpRequestFactory`, which is HTTP/1.1-only.
  - Fix: pin `extractorRestClient` to HTTP/1.1 via `JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HTTP_1_1))`. One bean, three lines.
  - **`ExtractorClientTest` passed throughout and always had.** `MockRestServiceServer` replaces the transport, so it *structurally cannot* catch a transport bug. The JSON contract it guards was fine; the wire was not. **A green mock-based suite is not evidence the integration works** — that gap is still open, and closing it needs Testcontainers.
  - Second cause of the two-session delay: `ExtractService` swallowed the exception and returned `UNREACHABLE`, the same value a genuinely dead page returns. Now `log.warn`s with the exception. **Reusing a status to mean two different things is what hid this.**
- **All four `/extract` branches verified live** after the fix: `OK` (rbi.org.in, 378 chars), `PAYWALLED` (example.com), `TOO_LARGE` (Wikipedia at 1.07MB vs the 200KB cap), `UNREACHABLE` (bad domain) — and exactly 2 Redis keys written at 7d TTL, confirming `TOO_LARGE`/`UNREACHABLE` are not cached.
- **Worth revisiting: the 200KB cap rejects real pages.** Wikipedia is 1.07MB, thehindu.com's front page 322KB. `TOO_LARGE` will be common in practice, not exceptional. That number is a guardrail config value, not a constant — user's call.

**`DomainRateLimiter` wired into the fetch path (PLAN Session 4, item 3 of 5).** It had been a `@Component` nothing injected — a rate limiter that limited nothing.
- Four decisions settled before any code: (1) the check lives in `ExtractService.extractOne()` between the cache read and the fetch, so a **cache hit never spends a token**; (2) refusal returns a new status `RATE_LIMITED` rather than reusing `UNREACHABLE` — today's bug is the argument for not overloading a status again; (3) **wait, don't fail fast**, since dropping a good URL to enforce politeness makes the research worse; (4) not cached, same reasoning as `UNREACHABLE`.
- `awaitToken(String)`: ask first, sleep second (so the attempt count is exactly `maxWaitAttempts`, not one more), sleep derived as `1000 / refillRate` rather than hardcoded — at `refill-rate: 0.5` a token needs 2s and a fixed 1s sleep would burn every attempt on nothing. `InterruptedException` restores the flag via `Thread.currentThread().interrupt()` and returns false.
- `RateLimitProperties` gained `int maxWaitAttempts`. **An unbound YAML key doesn't error — Spring just ignores it**, so adding `max-wait-attempts` to the YAML without a matching record component would have been a clean startup and a setting that did nothing. Same family as the 422: silence is not success.
- **7 new tests** written before the implementation, each pinning one of the four decisions; suite 60 → 67. `FAST_REFILL = 1000.0` in the test makes a 3-attempt wait finish in microseconds instead of 3 real seconds — the same payoff the injected clock gave `TokenBucket`.

**Session-method note:** the decisions were settled by discussion, but the loop body itself was written by Claude on request after "i donno what to write". Consistent with Session 12's rule — once what's left is mechanics rather than judgement, the correction loop has stopped teaching.

**A UI was built (explicitly requested, overriding `CLAUDE.md`'s "rich UI is cut").**
- `retrieval-service/src/main/resources/static/`: `index.html`, `app.js`, `topology.js`, `charts.js`. React 18 + `htm` from CDN — **no npm, no build step, no bundler, no new Maven dependency**. Spring serves `static/` automatically; the browser is the runtime. There is no second application to start.
- Four views: Overview (live topology + build progress), Retrieve, Extract (both live), Verify (**scripted mock, labelled as such** — `control-plane` has no endpoints, so `SUBQUESTIONS`/`CLAIMS` are constants; `runMock()` becomes an `EventSource` when the orchestrator lands and nothing else changes).
- Two bugs the process caught that would otherwise have shipped: the palette validator found **SUPPORTED (lime) vs PARTIAL (amber) were ΔE 1.4 apart under deuteranopia** — the most important signal in the app, indistinguishable for red-green colourblind readers; re-stepped to teal/orange/violet at ΔE 15.3. And **two classic `<script>`s both declaring top-level `const html` is a fatal SyntaxError** before anything renders — everything is IIFE-scoped now.
- **Trap worth remembering:** `mvn spring-boot:run` serves from `target/classes`, not `src/`. Editing `static/` and refreshing the browser shows the *old* file until the app is restarted. Same shape as the stale JVM in Session 3 and the `noeviction` Redis container in Session 10.

**Decided (deployment, no money spent):**
- Measured, not guessed: containers idle at **816 MB**; JVM reports 60 MB heap + 57 MB metaspace, so ~350 MB RSS per Spring service. Week 5 end state ≈ **5.3 GB idle, 7.2 GB peak** with KEDA at 6 replicas. **8 GB is the floor; 5 GB does not fit with k3s + KEDA** — the floor is infrastructure (Redpanda 1.1 GB, k3s 800 MB), which does not scale down with user count. 4 vCPU matters because the Week 4 speedup benchmark would otherwise measure the box, not the design.
- Cheapest real month-to-month: **Contabo Cloud VPS 4** (4 vCPU / 8 GB / 100 GB SSD) at $7.79 + 18% GST ≈ **₹873/mo**, India DC available, KVM so k3s runs fine. **Hetzner CX33 ≈ ₹995** but bills **hourly with a monthly cap** — at weekend-only usage that's ≈ ₹135/mo, which suits a box that will be destroyed and rebuilt repeatedly. Oracle Always Free was **halved in June 2026** to 2 OCPU / 12 GB; 12 GB still fits but 2 cores is under the benchmark's needs.
- **A domain is not a prerequisite** — `nip.io` gives a free hostname off the VM IP that Let's Encrypt will issue a real cert for. Buying early only burns the 12-month clock.
- **Not deploying yet, and that's correct**: Week 5's headline demo is KEDA scaling on Kafka consumer lag, and `agent-service` has no consumer. There is no lag to scale on until Week 2 exists.
- UI placement decided: `static/` moves to `control-plane` when that service gets endpoints — it's the only service with an ingress in Week 5, so the only one a browser can reach. A separate FE origin is deferred; the cost isn't the container, it's CORS plus cross-origin JWT handling.

**Week 1 Session 4 is now 3 of 5.** Done: cache-aside, Lua token bucket (now verified), limiter wired into the fetch path. Remaining, all the user's column: `CompletableFuture` fan-out, single-flight lock (`SET NX PX 30000`), `Semaphore(4)`, and the done-when (20 concurrent identical `/search` → 1 upstream call, 19 cache hits).

**Not done / still open:**
- No integration test touches a real Redis or the real sidecar. Every Redis interaction is mocked, which is precisely why the h2c bug survived. **Testcontainers is the fix and it is not written.**
- Quota is still a gauge, not a cutoff — nothing checks `remaining()` before spending.
- Guardrails still ~3% of the 7-item table; none of the limits built so far read from a `guardrails:` tree.
- `docs/story/` (10 files) and `graphify-out/` still untracked; the latter should be gitignored, not committed.

**Next session starts with:** `CompletableFuture` fan-out in `ExtractService.extract()` (`allOf()` + per-future `exceptionally()`), which also makes `awaitToken`'s blocking wait cheap. Then the single-flight lock, `Semaphore(4)`, and the 20-concurrent verification. That closes Week 1, open since 2026-08-08.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Prose style

Apply the `humanizer` skill's patterns to prose you write in this project — explanations, code walkthroughs, session notes, PR and commit descriptions, docs — not only when explicitly asked to edit text.

Scope:
- Applies to: written explanations and any human-facing document.
- Does not apply to: code, code comments, config, log lines, test names, or raw tool output.

The rule is the skill's own: every sentence kept must add something the reader did not already have. No not-X-but-Y contrasts, staged openers, one-line closers, forced triads, or stock AI vocabulary. Precision over polish — do not soften a technical claim to make it read smoother.

### Ponytail scope

Ponytail governs code, not prose — its own Boundaries section says so. In this project its "at most three short lines" output rule applies to *change summaries only*.

When asked to explain, walk through, review, or document, write the full explanation. Ponytail's brevity rule does not cap it. Laziness applies to the diff, never to the reader's understanding.
