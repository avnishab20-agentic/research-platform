# The Story of This Codebase — 91: Glossary

*Every term, annotation, pattern, and acronym used in this repo or in these docs.
One or two plain sentences each, plus where it shows up. Blueprint-only terms are
marked 🧱 (exists in docs/infra, not yet in code).*

---

**Aggregator pom** — a Maven project whose only job is listing child modules so one
command builds them all. Root `pom.xml:10-16`.

**Actuator** — Spring Boot's built-in ops endpoints; here just `/actuator/health`,
the "are you alive" URL. All three services expose it.

**allkeys-lru** — a Redis eviction policy: when the memory cap is hit, throw away the
least-recently-used key, whatever it is. Chosen because everything we store is a
re-fetchable cache. Set via `docker-compose.yml:22`.

**Annotation** — a `@Word` tag on Java code that doesn't run by itself; it's a label
something else reads later. Nearly all "framework magic" here starts with one
(`@Service`, `@PostMapping`, …).

**Auto-configuration** — Spring Boot noticing which jars you have and pre-building
sensible beans for them (e.g. seeing the Redis starter and creating
`StringRedisTemplate`). You never call it; it happens during boot.

**Bean** — an object that Spring built, owns, and wires into other objects. The
singleton citizens of the app.

**Cache-aside** — the pattern where *your code* checks the cache first, fetches from
the source only on a miss, then writes the cache itself. Hand-rolled in
`SearchService.search()` (`:53-66`); the project refuses `@Cacheable` on purpose
(docs/PLAN.md:77-81).

**Checksum** — a fingerprint of a file; Flyway stores one per applied migration and
fails boot if an applied SQL file later changes.

**Classpath** — the list of code libraries (jars/classes) the JVM can load;
auto-configuration reads it like a shopping receipt.

**Component scan** — Spring walking your packages at boot, registering every
annotated class as a bean. Triggered by `@SpringBootApplication`.

**Constructor injection** — dependencies arrive through the constructor, set once at
birth, `final` ever after. House rule (CLAUDE.md Conventions); see
`SearchService.java:33-39`.

**Container (Docker)** — a sealed mini-computer running one process with its own
filesystem; five of them at runtime (`docker-compose.yml`).

**contextLoads** — the simplest possible test: "boot the entire Spring context and
report if anything exploded." Exists once per service.

**Controller** — the class that receives HTTP requests at the front door. Here:
`RetrievalController`.

**ddl-auto=validate** — Hibernate mode meaning "compare my entity classes against the
real schema, change nothing." With zero entities it checks nothing
(`control-plane/application.properties:5`).

**Dependency injection (DI)** — the arrangement where Spring hands each class its
dependencies instead of classes fetching their own. Why `SearchService` never says
`new QuotaService()`.

**Deviation (recorded)** — a documented, deliberate divergence from the plan instead of
a silent one; the repo keeps them in `docs/PLAN.md` blockquotes and CLAUDE.md session
notes (e.g. the search-key formula, PLAN lines 42-51).

**DI ambiguity / `@Qualifier`** — when two beans share a type, Spring refuses to
guess; `@Qualifier("name")` on the injection point names the one you want. Lives on
constructor *parameters* here (`SearchService.java:33`, `ExtractorClient.java:16`).

**Docker image vs container** — image = the frozen recipe snapshot; container = a
running instance of it. One image, N containers.

**Docker layer** — one instruction's cached output inside an image; order lines
rarely-changing-first so edits stay cheap (`extractor/Dockerfile:3-5`).

**docker-compose** — one YAML describing and starting all containers together, with
port maps and healthchecks.

**DTO** — data transfer object; a shape that exists to cross a boundary. All records
in `dto/` plus the SearXNG/extractor response records.

**Embedded Tomcat** — the HTTP server inside your jar; the reason `java -jar` is a
web server with no install step.

**Endpoint** — one URL+verb+handler combo, e.g. `POST /api/v1/search`.

**Entry point** — any doorway where the outside world triggers your code: HTTP
routes, healthchecks, boot hooks, tests. Inventory lives in the story intro.

**Eager vs lazy connection** — eager: connect at boot, fail boot if down (control-plane
→ Postgres). Lazy: connect at first use, fail the *request* instead (retrieval →
Redis via Lettuce).

**Fan-out / fan-in** 🧱 — send one job to many parallel workers (Kafka), then collect
all results before continuing (Postgres counter). The Week-2 shape; tables already
built (`V1__init_schema.sql`).

**FastAPI** — Python's decorator-driven web framework; the sidecar's Spring MVC.

**Flyway migration** — a numbered, run-once SQL file that evolves the schema;
`V1__init_schema.sql` is the only one.

**Framework magic** — shorthand for code that runs without a visible caller because a
framework read your annotations and calls you. Each story chapter has a roundup table
for exactly this.

**Glob vs regex** — two wildcard languages; `*.blogspot.*` is a glob, converted to
regex `.*\.blogspot\..*` at `SourceTierResolver.java:53-56`.

**Healthcheck** — a command Docker runs inside a container on a timer to label it
healthy/unhealthy (`docker-compose.yml:69-73`). Labels only — no restart policy exists.

**Hibernate / JPA** — the ORM stack (objects↔tables). Present in control-plane for
Week 2; currently validates zero entities.

**HTTP 400 / 422 / 500** — bad request (your JSON is wrong, Spring's verdict before
our code), unprocessable (pydantic's verdict, sidecar), internal error (our code
threw, nobody caught).

**Idempotent** — safe to repeat: same input, no additional harm. Retrying the search
flow after a SearXNG failure is idempotent-ish (nothing was cached or charged).

**INCR / EXPIRE** — Redis commands: increment a counter string; set seconds-until-
vanish. The daily quota is both, refreshed on every spend (`QuotaService.java:23-24`).

**Jackson (v3)** — the JSON translator, incoming and outgoing; note the modern
`tools.jackson` package (`SearchService.java:12-13`).

**JSONB** — Postgres's column type for storing JSON in a fast, queryable form;
`runs.report` and finding columns use it (`V1__init_schema.sql:5,17`).

**JUnit 5** — the test runner behind all 17 green checks.

**Kafka / Redpanda / topic / partition / offset / consumer group** 🧱 — a message
broker and its vocabulary: topics are named mail slots, partitions the parallel lanes
inside a slot, offset a reader's bookmark, consumer group a team sharing the reading.
Redpanda is Kafka-compatible; three topics exist server-side, zero Java code touches
them yet.

**Lettuce** — the network client library under Spring Data Redis; keeps pooled
connections, connects lazily.

**Lombok** — a code-generation library this repo deliberately does NOT use; records
replace it.

**maxmemory** — Redis's self-imposed RAM cap (256MB here). With allkeys-lru it turns
"full" into "recycle oldest" instead of "refuse writes."

**Maven multi-module** — one parent recipe, several child jars; `-pl X -am` builds X
plus its dependencies.

**Normalization (URL/query)** — rewriting cosmetic variants into one canonical form so
caches don't fork. Query: `SearchService.java:83-87`; URL: `UrlNormalizer.java:16-34`.

**pgvector** — Postgres extension for AI embedding vectors; why the image is
`pgvector/pgvector:pg16` (`docker-compose.yml:3`). Unused so far.

**Port mapping** — `"host:container"` wiring so your Mac's 8000 reaches the
container's 8000 (`docker-compose.yml:68`).

**pydantic** — Python's validate-the-JSON-into-a-class library; the sidecar's bouncer
(`main.py:10-20`).

**Record (Java)** — immutable data carrier with free constructor/accessors/equals;
the house DTO style.

**Relaxed binding** — Spring's rule for matching a YAML key like `tier4-patterns` to a
Java field named `tier4Patterns`, even though punctuation differs.

**RestClient** — Spring's fluent outbound HTTP client; two beans exist, each named
and each `@Qualifier`-claimed.

**Route table** — the lookup table matching each URL to the method that handles it,
built at startup by Spring MVC/uvicorn from decorators/annotations.

**Semaphore** 🧱 — a "max N people inside" ticket counter; PLAN reserves one (N=4) to
shield the sidecar from concurrent stampedes (docs/PLAN.md:97-98).

**Sequence diagram** — the plain-text chart in each flow chapter: participants as
rows, arrows between them in time order. (Originally drawn in Mermaid; replaced with
plain code-fenced text blocks so they render inside the IDEA editor too.)

**Serialization** — turning objects into bytes (JSON here) and back; happens at both
HTTP doors and on both Redis writes/reads.

**SHA-256** — a one-way fingerprint function; fixed-length hex, same input → same
output. `hash/Hashing.java:13-21`.

**Sidecar** — a small helper service deployed alongside the main ones; here the
Python boilerplate-stripper. It never fetches; callers bring HTML.

**Single-flight / thundering herd** 🧱 — many concurrent requesters wanting the same
uncached key; the planned fix is one winner fetches (`SET NX` lock) while losers wait
and re-read (docs/PLAN.md:92-95).

**Spring profile** 🧱 — a named operating mode of one app; the plan is RESEARCHER/
WRITER/CRITIC as profiles of agent-service, not separate deployables.

**SSE (Server-Sent Events)** 🧱 — one-way HTTP streaming from server to browser;
planned for the Week-4 report page.

**Stateless** — keeps no memory between calls; the sidecar is fully stateless, which
is why it has nothing to clean up after failures.

**StringRedisTemplate** — Spring's wrapper object whose methods are Redis commands
(`get`, `set`, `increment`, `expire`).

**Stub** — a real endpoint with a fake body, proving the contract before the logic
exists. `RetrievalController.extract()` `:26-29`.

**Testcontainers** 🧱 — the convention (CLAUDE.md) of spinning throwaway Docker
containers for integration tests; not yet used — today's `contextLoads` tests lean on
live containers.

**Tier (source quality)** — 1 = official/primary, 2 = reputable press, 3 = default
unknown, 4 = blog-pattern. YAML honor roll at `application.yml:1-12`.

**Tomcat thread pool** — the finite set of worker threads that run your controllers;
one thread per in-flight request, concurrency included free of charge.

**TTL (time to live)** — how long a Redis key may live before auto-vanishing: 24h
search (`SearchService.java:63`), 24h quota (`QuotaService.java:24`), 7d extract
planned (docs/PLAN.md:57).

**Trafilatura** — the Python library that finds the article inside messy HTML
(`main.py:30-31`).

**TypeReference** — Jackson's trick for turning JSON into a typed `List<…>` without
losing the element type (`SearchService.java:58-59`).

**UUID** — 128-bit random identifier; primary keys of the blueprint tables
(`V1__init_schema.sql:2,11`).

**uvicorn** — the sidecar's HTTP server process (Python's Tomcat).

**YAML** — indentation-defined config format; `application.yml` and
`docker-compose.yml` are both YAML (indentation errors are the classic failure).
