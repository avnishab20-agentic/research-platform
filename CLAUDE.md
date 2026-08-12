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
- VM deployment, CI/CD, Langfuse
- Rich UI (one plain SSE page only)
- Auth, multi-user, billing

### Do not add
- New services. Anything that feels like a new service is a Spring profile or a class.
- New dependencies without asking first.
- Kubernetes, service mesh, API gateway, Terraform.

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
