# Graph Report - research-platform  (2026-08-28)

## Corpus Check
- 71 files · ~61,526 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 357 nodes · 529 edges · 38 communities (28 shown, 10 thin omitted)
- Extraction: 89% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 54 edges (avg confidence: 0.86)
- Token cost: 274,976 input · 0 output

## Community Hubs (Navigation)
- Retrieval API Layer & DTOs
- Boot & Cache-Key Tests
- Postgres Fan-In & Working Agreement
- RestClient Beans & Extractor Client
- Source Tiering Configuration
- Concurrency & Sidecar Extraction
- Quota Metering Rationale
- SearXNG Search & Cache Keys
- Redis Caching Infrastructure
- URL Normalization & Hashing
- Spring Application Entrypoints
- agent-service Maven Wrapper
- Claim Contracts & Critic Loop
- control-plane Maven Wrapper
- Cache-Key Correctness Cast
- retrieval-service Maven Wrapper
- Week 5 Kubernetes & KEDA
- Blueprint vs Built Scope
- Python Extractor FastAPI App
- Control Plane Orchestration & Kafka
- Quota Boundary & Rate Limiting
- Plan, SSE & Session Artifacts
- Build Traps & Error-Handling Gaps
- Docker Compose Runtime Stack
- Agent Roles as Spring Profiles
- Project Scope & Messaging Choices
- Cross-Language Field Contracts
- SearXNG Response Records
- Health Checks & Network Binding
- OAuth2 Stretch Goal
- Docker Layer Caching
- Test Stack Dependencies
- Sidecar URL Echo
- agent-service Module
- Root Maven Aggregator
- common Module
- control-plane Module
- retrieval-service Module

## God Nodes (most connected - your core abstractions)
1. `SearchService` - 15 edges
2. `QuotaService` - 13 edges
3. `SourceTierResolver` - 10 edges
4. `RetrievalController` - 9 edges
5. `UrlNormalizerTest` - 9 edges
6. `SearchService (the librarian)` - 9 edges
7. `SourceTierResolverTest` - 8 edges
8. `Research Platform (multi-agent research system)` - 8 edges
9. `SourceTierProperties` - 7 edges
10. `UrlNormalizer` - 7 edges

## Surprising Connections (you probably didn't know these)
- `AGENTS.md project brief (stale mirror of CLAUDE.md)` --semantically_similar_to--> `Research Platform (multi-agent research system)`  [INFERRED] [semantically similar]
  AGENTS.md → CLAUDE.md
- `Three-layer rate limiting` --semantically_similar_to--> `Retrieval is the quota boundary`  [INFERRED] [semantically similar]
  docs/PLAN.md → CLAUDE.md
- `Preference: docs must be accurate and readable by a non-technical reader` --semantically_similar_to--> `Working agreement (learning-first division of labour)`  [INFERRED] [semantically similar]
  .commandcode/taste/taste/taste.md → CLAUDE.md
- `Hex-encoded session handoff artifact` --semantically_similar_to--> `CLAUDE.md session log (Sessions 1-10)`  [INFERRED] [semantically similar]
  docs/SESSION_HANDOFF.md → CLAUDE.md
- `AGENTS.md project brief (stale mirror of CLAUDE.md)` --conceptually_related_to--> `Known documentation discrepancies`  [AMBIGUOUS]
  AGENTS.md → docs/RESEARCH_PLATFORM_GUIDE.html

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Postgres-backed fan-in and restart survival** — claude_postgres_fan_in_counter, claude_no_in_memory_fan_in, claude_deadline_sweeper, docs_plan_fan_in_sql, docs_architecture_db_schema, control_plane_readme_fan_in_mechanism, docs_learning_fan_in_race, docs_plan_restart_survival_demo [EXTRACTED 1.00]
- **Claim-by-claim verification pipeline** — claude_structured_claims, claude_critic_evidence_passage, docs_plan_writer_claim_records, docs_plan_critic_loop, docs_plan_fabrication_injection_eval, agent_service_readme_writer_role, agent_service_readme_critic_role, docs_plan_sse_page [EXTRACTED 1.00]
- **Hand-written Redis and concurrency patterns (PLAN Session 4)** — docs_plan_manual_cache_aside, docs_plan_token_bucket_lua, docs_plan_completable_future_fetch, docs_plan_single_flight, docs_plan_semaphore_guard, docs_plan_cache_key_scheme [EXTRACTED 1.00]
- **The idle extract chain: courier, label maker, detective, sidecar** — docs_story_00_the_world_extractorclient, docs_story_00_the_world_extractcachekey, docs_story_00_the_world_urlnormalizer, docs_story_00_the_world_extractor_sidecar, docs_story_03_extract_stub_flow_wired_but_never_called [EXTRACTED 1.00]
- **The search flow end to end** — docs_story_00_the_world_retrievalcontroller, docs_story_00_the_world_searchservice, docs_story_00_the_world_redis, docs_story_00_the_world_searxng, docs_story_00_the_world_quotaservice, docs_story_00_the_world_sourcetierresolver, docs_story_01_search_flow_cache_aside [EXTRACTED 1.00]
- **Versioned, canonicalized cache-key discipline** — docs_story_01_search_flow_cache_key_correctness_rule, docs_story_01_search_flow_hashing_is_tidiness_not_correctness, docs_story_01_search_flow_key_versioning, docs_story_00_the_world_hashing, docs_story_00_the_world_urlnormalizer, docs_story_00_the_world_extractcachekey [INFERRED 0.85]

## Communities (38 total, 10 thin omitted)

### Community 0 - "Retrieval API Layer & DTOs"
Cohesion: 0.09
Nodes (17): GetMapping, org.springframework.data.redis.core.StringRedisTemplate, org.springframework.stereotype.Service, PostMapping, RequestMapping, RestController, Document, ExtractRequest (+9 more)

### Community 1 - "Boot & Cache-Key Tests"
Cohesion: 0.15
Nodes (7): AgentServiceApplicationTests, ControlPlaneApplicationTests, org.junit.jupiter.api.Test, org.springframework.boot.test.context.SpringBootTest, ExtractCacheKeyTest, RetrievalServiceApplicationTests, UrlNormalizerTest

### Community 2 - "Postgres Fan-In & Working Agreement"
Cohesion: 0.10
Nodes (22): Taste index (pointer file), Preference: documentation tasks must not touch source code, Preference: docs must be accurate and readable by a non-technical reader, Preference: run the verify-then-fix loop end to end without asking, Preference: internal markdown links must actually resolve, Preference: parallelize independent work across subagents, Deadline sweeper for stuck levels, Fan-in state must not live in a ConcurrentHashMap or AtomicInteger (+14 more)

### Community 3 - "RestClient Beans & Extractor Client"
Cohesion: 0.18
Nodes (9): org.springframework.context.annotation.Bean, org.springframework.context.annotation.Configuration, org.springframework.stereotype.Component, org.springframework.web.client.RestClient, ExtractClientConfig, SearxngClientConfig, ExtractorClient, ExtractorRequest (+1 more)

### Community 4 - "Source Tiering Configuration"
Cohesion: 0.21
Nodes (5): org.springframework.boot.context.properties.ConfigurationProperties, QuotaProperties, SourceTierProperties, SourceTierResolver, SourceTierResolverTest

### Community 5 - "Concurrency & Sidecar Extraction"
Cohesion: 0.13
Nodes (15): extractor sidecar (Python/trafilatura), Trap: max-poll-interval-ms default evicts mid-work consumers, extractor service (build: ./extractor), CompletableFuture default executor pitfall, Semaphore vs fixed thread pool, Thundering herd / cache stampede, Virtual threads: when they help, Parallel URL fetching with CompletableFuture (+7 more)

### Community 6 - "Quota Metering Rationale"
Cohesion: 0.16
Nodes (15): Suspicious: the quota is a fuel gauge, not a fuel cutoff, QuotaService (the meter reader), Records, not Lombok, RetrievalController (the doorman), SearchService (the librarian), SourceTierResolver (the quality grader), Everything expires on its own (no cleanup job), Filter before limit (stream stage order is meaning) (+7 more)

### Community 7 - "SearXNG Search & Cache Keys"
Cohesion: 0.15
Nodes (14): Trap: SearXNG returns HTML unless json is in search.formats, Versioned cache keys (search:v1:), redis service (redis:7, allkeys-lru), searxng service (port 8080), Cache raw SearXNG results before tiering, POST /api/v1/search decision-by-decision flow, Deviation: maxResults and minTier dropped from the search key, Versioned cache key scheme (+6 more)

### Community 8 - "Redis Caching Infrastructure"
Cohesion: 0.18
Nodes (13): Redis allkeys-lru eviction with 256MB cap, control-plane (port 8083, blueprint), Postgres 16 + pgvector, Flyway, Hibernate validate, Redis 7 (Lettuce client), Cache-aside pattern (hand-written), Cache stampede / thundering herd, Versioned key prefix (search:v1:searxng:), Single-flight lock (planned, unbuilt) (+5 more)

### Community 9 - "URL Normalization & Hashing"
Cohesion: 0.21
Nodes (3): ExtractCacheKey, Hashing, UrlNormalizer

### Community 10 - "Spring Application Entrypoints"
Cohesion: 0.25
Nodes (5): AgentServiceApplication, ControlPlaneApplication, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.boot.context.properties.ConfigurationPropertiesScan, RetrievalServiceApplication

### Community 11 - "agent-service Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 12 - "Claim Contracts & Critic Loop"
Cohesion: 0.24
Nodes (10): common module (shared records, no main class), Coding conventions (records, RestClient, Flyway-first, constructor injection), Critic re-fetches sources and stores the matched evidence passage, The writer emits structured claims, not prose, Shared data-shape contracts (Claim, ResearchFinding), Monorepo over polyrepo, Shared common module vs schema-first contracts, Critic loop and re-research gate (+2 more)

### Community 13 - "control-plane Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 14 - "Cache-Key Correctness Cast"
Cohesion: 0.20
Nodes (10): ExtractCacheKey (the label maker), Hashing (the fingerprint clerk), UrlNormalizer (the alias detective), Cache-key correctness rule, Hashing is tidiness, not correctness, Semaphore(4) in front of the sidecar (planned), Wired but never called, The caller fetches, the sidecar cleans (+2 more)

### Community 15 - "retrieval-service Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 16 - "Week 5 Kubernetes & KEDA"
Cohesion: 0.22
Nodes (9): Immutable SHA image tags over :latest, Why KEDA on only one service (LEARNING.md), Migrations must be backward-compatible with the previous image, Why no Eureka (LEARNING.md), CI/CD pipeline (moved to Week 5), KEDA ScaledObject on agent-service only, Explicitly not doing Eureka / service discovery, Postgres/Redpanda/Redis stay as plain Docker, not in k3s (+1 more)

### Community 17 - "Blueprint vs Built Scope"
Cohesion: 0.22
Nodes (9): Blueprint props (built, unused), common module (empty toolbox), Suspicious: common/README claims a dependency nothing declares, Python extractor sidecar, ExtractorClient (the unemployed courier), Two RestClient beans and @Qualifier ambiguity, MIN_TEXT_CHARS = 200 paywall heuristic, Sidecar statelessness (+1 more)

### Community 18 - "Python Extractor FastAPI App"
Cohesion: 0.36
Nodes (7): BaseModel, extract(), ExtractIn, ExtractOut, health(), get, post

### Community 19 - "Control Plane Orchestration & Kafka"
Cohesion: 0.29
Nodes (8): control-plane (port 8083), redpanda service (Kafka API, dual listener), Kafka topics (research.subtasks / research.findings / agent.events), Run lifecycle (10 steps, submit to deliver), Whole-system architecture diagram (built vs planned), Where auth lives and where it does not, Flat fan-out (6-8 level-0 sub-questions), Spring Security + JWT on control-plane only

### Community 20 - "Quota Boundary & Rate Limiting"
Cohesion: 0.29
Nodes (8): Retrieval is the quota boundary, retrieval-service (port 8081), Cache-aside as an interview story, Atomic check-then-act in Redis via Lua, Cache-aside written manually (no @Cacheable), Three-layer rate limiting, Per-domain token bucket as a Redis Lua script, quota.daily-limit property

### Community 21 - "Plan, SSE & Session Artifacts"
Cohesion: 0.25
Nodes (8): CLAUDE.md session log (Sessions 1-10), SSE progress streaming, Thread pool sizing and CountDownLatch harness, Fixture mode (replay recorded search + LLM responses), 4-Week Plan, Speedup benchmark (concurrency 1 vs 6), SSE page (plain HTML, no framework), Hex-encoded session handoff artifact

### Community 22 - "Build Traps & Error-Handling Gaps"
Cohesion: 0.25
Nodes (8): agent-service (port 8082, blueprint), Suspicious: java.version never wired to the compiler, Maven multi-module aggregator build, Redpanda (Kafka-compatible broker), No error handling anywhere in the search flow, Crash loudly on a corrupt counter, Reading the gauge never burns fuel, Check Yourself (15 questions)

### Community 23 - "Docker Compose Runtime Stack"
Cohesion: 0.25
Nodes (8): Docker + docker-compose (5 containers), Problem: search ranks, it never verifies, retrieval-service (port 8081), SearXNG metasearch container, Spring Boot 4.1.0 + embedded Tomcat, Two ignition switches (compose up, then the Java apps), Plain-text diagrams instead of Mermaid, The Story of This Codebase (guide)

### Community 24 - "Agent Roles as Spring Profiles"
Cohesion: 0.43
Nodes (7): CRITIC role, RESEARCHER role, WRITER role, Agent types are Spring profiles, not separate deployables, agent-service (port 8082), The researcher loop (one sub-question), retrieval-service endpoint contract (search / extract / quota)

### Community 25 - "Project Scope & Messaging Choices"
Cohesion: 0.50
Nodes (5): AGENTS.md project brief (stale mirror of CLAUDE.md), Explicit scope cuts (weeks 1-4), Kafka for durable hops, HTTP for cache lookups, Research Platform (multi-agent research system), Redis vs Kafka in the same system

### Community 26 - "Cross-Language Field Contracts"
Cohesion: 0.40
Nodes (5): Jackson 3 (tools.jackson.*), content renamed to snippet at the API boundary, Record field names ARE the public contract, camelCase publishedAt across the language wall, pydantic validation (HTTP 422 before our code)

### Community 28 - "Health Checks & Network Binding"
Cohesion: 0.67
Nodes (3): Actuator /actuator/health, Healthcheck as a Python one-liner, --host 0.0.0.0 vs loopback

## Ambiguous Edges - Review These
- `Working agreement (learning-first division of labour)` → `Preference: run the verify-then-fix loop end to end without asking`  [AMBIGUOUS]
  .commandcode/taste/taste/taste.md · relation: conceptually_related_to
- `AGENTS.md project brief (stale mirror of CLAUDE.md)` → `Known documentation discrepancies`  [AMBIGUOUS]
  AGENTS.md · relation: conceptually_related_to

## Knowledge Gaps
- **23 isolated node(s):** `agent-service`, `common`, `control-plane`, `com.comeback.researchplatform:research-platform`, `retrieval-service` (+18 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Working agreement (learning-first division of labour)` and `Preference: run the verify-then-fix loop end to end without asking`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `AGENTS.md project brief (stale mirror of CLAUDE.md)` and `Known documentation discrepancies`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `SearchService` connect `Retrieval API Layer & DTOs` to `RestClient Beans & Extractor Client`, `Source Tiering Configuration`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Why does `SourceTierResolver` connect `Source Tiering Configuration` to `Retrieval API Layer & DTOs`, `RestClient Beans & Extractor Client`?**
  _High betweenness centrality (0.024) - this node is a cross-community bridge._
- **What connects `agent-service`, `common`, `control-plane` to the rest of the system?**
  _23 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Retrieval API Layer & DTOs` be split into smaller, more focused modules?**
  _Cohesion score 0.09230769230769231 - nodes in this community are weakly interconnected._
- **Should `Boot & Cache-Key Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.14814814814814814 - nodes in this community are weakly interconnected._