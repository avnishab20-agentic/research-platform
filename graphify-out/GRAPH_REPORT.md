# Graph Report - research-platform  (2026-08-29)

## Corpus Check
- 79 files · ~65,926 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 412 nodes · 627 edges · 54 communities (31 shown, 23 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 67 edges (avg confidence: 0.85)
- Token cost: 80,232 input · 0 output

## Community Hubs (Navigation)
- Spring Wiring and Config Beans
- Test Suite and Boot Contexts
- Architecture Decisions and Contracts
- Search DTOs and SearXNG Client
- Retrieval Service Cast and Quota
- Source Tiering and Properties
- Extract DTOs and Sidecar Contract
- Redis Caching and TTL Strategy
- Verification, Critic and Evals
- Cache Keys, Hashing, URL Normalization
- Application Entry Points
- Retrieval Endpoints and Fan-out
- Week Plan and Kubernetes Phase
- agent-service Maven Wrapper
- Agent Roles and Kafka Config
- control-plane Maven Wrapper
- Cache Key Narrative and Deviations
- retrieval-service Maven Wrapper
- Extractor Client and Blueprint Gaps
- Workflow Preferences and Docs Audit
- Python Extractor Sidecar
- Control Plane, SSE and Auth
- Infrastructure Containers and Schema
- Build Traps and Error Handling
- Project Premise and Local Stack
- Sidecar Concurrency and Deps
- Quota Boundary and Rate Limiting
- TokenBucket Rate Limiter
- Cross-language Field Naming
- Working Agreement and Conventions
- Explicitly Cut Scope
- Health Checks and Binding
- Coordination Theory Not Taught
- Docker Layer Caching
- Taste Index Pointer
- Markdown Link Portability
- Fan-in Mechanism Note
- Cache-aside Interview Story
- Fan-in Race War Story
- Immutable Image Tags
- Backward-compatible Migrations
- OAuth2 Client vs Server
- Redis vs Kafka Roles
- Semaphore vs Thread Pool
- Thread Pool Sizing Harness
- Thundering Herd
- Hex Session Handoff
- Test Stack Resolution
- URL Echo Preference
- agent-service Module
- Root Aggregator POM
- common Module
- control-plane Module
- retrieval-service Module

## God Nodes (most connected - your core abstractions)
1. `SearchService` - 15 edges
2. `ExtractService` - 13 edges
3. `QuotaService` - 13 edges
4. `SourceTierResolver` - 13 edges
5. `ExtractProperties` - 10 edges
6. `RetrievalController` - 10 edges
7. `PageFetcherTest` - 10 edges
8. `PageFetcher` - 9 edges
9. `UrlNormalizerTest` - 9 edges
10. `SearchService (the librarian)` - 9 edges

## Surprising Connections (you probably didn't know these)
- `AGENTS.md project brief (stale mirror of CLAUDE.md)` --semantically_similar_to--> `Research Platform (multi-agent research system)`  [INFERRED] [semantically similar]
  AGENTS.md → CLAUDE.md
- `Shared common module vs schema-first contracts` --semantically_similar_to--> `Shared data-shape contracts (Claim, ResearchFinding)`  [INFERRED] [semantically similar]
  docs/LEARNING.md → common/README.md
- `Documentation Tasks Must Not Touch Source Code` --semantically_similar_to--> `Working Agreement (who writes what)`  [INFERRED] [semantically similar]
  .commandcode/taste/taste/taste.md → CLAUDE.md
- `Parallelize Independent Chunks via Subagents` --semantically_similar_to--> `Parallel URL Fetching with CompletableFuture`  [INFERRED] [semantically similar]
  .commandcode/taste/taste/taste.md → docs/PLAN.md
- `AGENTS.md project brief (stale mirror of CLAUDE.md)` --conceptually_related_to--> `Known documentation discrepancies`  [AMBIGUOUS]
  AGENTS.md → docs/RESEARCH_PLATFORM_GUIDE.html

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Postgres-backed fan-in and restart survival** — claude_deadline_sweeper, docs_architecture_db_schema, control_plane_readme_fan_in_mechanism, docs_learning_fan_in_race, docs_plan_restart_survival_demo [EXTRACTED 1.00]
- **The search flow end to end** — docs_story_00_the_world_retrievalcontroller, docs_story_00_the_world_searchservice, docs_story_00_the_world_redis, docs_story_00_the_world_searxng, docs_story_00_the_world_quotaservice, docs_story_00_the_world_sourcetierresolver, docs_story_01_search_flow_cache_aside [EXTRACTED 1.00]
- **Versioned, canonicalized cache-key discipline** — docs_story_01_search_flow_cache_key_correctness_rule, docs_story_01_search_flow_hashing_is_tidiness_not_correctness, docs_story_01_search_flow_key_versioning, docs_story_00_the_world_hashing, docs_story_00_the_world_urlnormalizer, docs_story_00_the_world_extractcachekey [INFERRED 0.85]
- **The idle extract chain: courier, label maker, detective, sidecar** — docs_story_00_the_world_extractorclient, docs_story_00_the_world_extractcachekey, docs_story_00_the_world_urlnormalizer, docs_story_00_the_world_extractor_sidecar, docs_story_03_extract_stub_flow_wired_but_never_called [EXTRACTED 1.00]
- **Week 1 Session 4 hand-written concurrency patterns** — docs_plan_cache_aside_manual, docs_plan_token_bucket_lua, docs_plan_parallel_fetch_completablefuture, docs_plan_cache_stampede_single_flight, docs_plan_extractor_semaphore [EXTRACTED 1.00]
- **Verification pipeline: structured claims to graded verdicts to badged report** — docs_plan_report_claim_records, docs_plan_critic_loop, docs_plan_unsupported_ratio, docs_plan_sse_page, claude_claim_verification [EXTRACTED 1.00]
- **Guardrails as one config tree bound to one record** — claude_guardrails_as_config, docs_plan_guardrails_config_tree, docs_plan_guardrailproperties_record, docs_plan_guardrails_global_mode, docs_plan_guardrail_tripwire_eval [EXTRACTED 1.00]

## Communities (54 total, 23 thin omitted)

### Community 0 - "Spring Wiring and Config Beans"
Cohesion: 0.11
Nodes (22): GetMapping, org.springframework.context.annotation.Bean, org.springframework.context.annotation.Configuration, org.springframework.data.redis.core.StringRedisTemplate, org.springframework.stereotype.Component, org.springframework.stereotype.Service, org.springframework.web.client.RestClient, RequestMapping (+14 more)

### Community 1 - "Test Suite and Boot Contexts"
Cohesion: 0.09
Nodes (11): AgentServiceApplicationTests, ControlPlaneApplicationTests, org.junit.jupiter.api.BeforeEach, org.junit.jupiter.api.Test, org.springframework.boot.test.context.SpringBootTest, org.springframework.test.web.client.MockRestServiceServer, FetchedPage, ExtractCacheKeyTest (+3 more)

### Community 2 - "Architecture Decisions and Contracts"
Cohesion: 0.09
Nodes (26): common module (shared records), Cost Bounded by Call Count, Not Dollars, A Failed Run is Published, Never Silently Dropped, Guardrails are Configuration, Not Code Paths, Versioned Cache Keys (bump, never invalidate), Shared data-shape contracts (Claim, ResearchFinding), redis service (redis:7, allkeys-lru), searxng service (port 8080) (+18 more)

### Community 3 - "Search DTOs and SearXNG Client"
Cohesion: 0.16
Nodes (5): SearchRequest, SearchResponse, SearchResult, SearxngResult, SearxngSearchResponse

### Community 4 - "Retrieval Service Cast and Quota"
Cohesion: 0.16
Nodes (15): Suspicious: the quota is a fuel gauge, not a fuel cutoff, QuotaService (the meter reader), Records, not Lombok, RetrievalController (the doorman), SearchService (the librarian), SourceTierResolver (the quality grader), Everything expires on its own (no cleanup job), Filter before limit (stream stage order is meaning) (+7 more)

### Community 5 - "Source Tiering and Properties"
Cohesion: 0.20
Nodes (4): org.springframework.boot.context.properties.ConfigurationProperties, QuotaProperties, SourceTierProperties, SourceTierResolverTest

### Community 6 - "Extract DTOs and Sidecar Contract"
Cohesion: 0.16
Nodes (6): PostMapping, Document, ExtractRequest, ExtractResponse, ExtractorRequest, ExtractorResult

### Community 7 - "Redis Caching and TTL Strategy"
Cohesion: 0.18
Nodes (13): Redis allkeys-lru eviction with 256MB cap, control-plane (port 8083, blueprint), Postgres 16 + pgvector, Flyway, Hibernate validate, Redis 7 (Lettuce client), Cache-aside pattern (hand-written), Cache stampede / thundering herd, Versioned key prefix (search:v1:searxng:), Single-flight lock (planned, unbuilt) (+5 more)

### Community 8 - "Verification, Critic and Evals"
Cohesion: 0.18
Nodes (12): Claim-by-claim Verification, Critic Stores the Matched Evidence Passage, Evals Run on Fixture Mode and Cost $0, Writer Emits Structured Claims, Not Prose, CI/CD Pipeline (moved to Week 5), Dedupe Near-identical Claims by Embedding Before Verifying, Critic Loop (embed, retrieve, grade, persist verdict), Fabrication Injection Eval (+4 more)

### Community 9 - "Cache Keys, Hashing, URL Normalization"
Cohesion: 0.23
Nodes (3): ExtractCacheKey, Hashing, UrlNormalizer

### Community 10 - "Application Entry Points"
Cohesion: 0.25
Nodes (5): AgentServiceApplication, ControlPlaneApplication, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.boot.context.properties.ConfigurationPropertiesScan, RetrievalServiceApplication

### Community 11 - "Retrieval Endpoints and Fan-out"
Cohesion: 0.18
Nodes (11): Kafka for Durable Hops, HTTP for Cache Lookups, retrieval-service (port 8081), SearXNG (self-hosted search), SearXNG Requires json in search.formats, Fan-in via dag_levels UPDATE ... RETURNING, Fan-out: Planner Emits 6-8 Flat Sub-questions, The Researcher Loop (8 steps), Restart-Survival Demo (+3 more)

### Community 12 - "Week Plan and Kubernetes Phase"
Cohesion: 0.20
Nodes (10): Cross-session 'continue' Resumes from Session Log / PLAN.md, Why KEDA on only one service (LEARNING.md), Why no Eureka (LEARNING.md), 4-Week Plan, KEDA ScaledObject on agent-service (consumer lag), Explicitly NOT Doing: Eureka / Service Discovery, Week 2 — Parallel Research, Week 3 — Verification (the differentiator) (+2 more)

### Community 13 - "agent-service Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 14 - "Agent Roles and Kafka Config"
Cohesion: 0.24
Nodes (10): CRITIC role, RESEARCHER role, WRITER role, Agent Types as Spring Profiles (RESEARCHER/WRITER/CRITIC), agent-service (port 8082), max-poll-interval-ms 5-minute Eviction Trap, CompletableFuture default executor pitfall, Virtual threads: when they help (+2 more)

### Community 15 - "control-plane Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 16 - "Cache Key Narrative and Deviations"
Cohesion: 0.20
Nodes (10): ExtractCacheKey (the label maker), Hashing (the fingerprint clerk), UrlNormalizer (the alias detective), Cache-key correctness rule, Hashing is tidiness, not correctness, Semaphore(4) in front of the sidecar (planned), Wired but never called, The caller fetches, the sidecar cleans (+2 more)

### Community 17 - "retrieval-service Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 18 - "Extractor Client and Blueprint Gaps"
Cohesion: 0.22
Nodes (9): Blueprint props (built, unused), common module (empty toolbox), Suspicious: common/README claims a dependency nothing declares, Python extractor sidecar, ExtractorClient (the unemployed courier), Two RestClient beans and @Qualifier ambiguity, MIN_TEXT_CHARS = 200 paywall heuristic, Sidecar statelessness (+1 more)

### Community 19 - "Workflow Preferences and Docs Audit"
Cohesion: 0.25
Nodes (8): Start Docker Desktop and Poll docker info Before Compose, Run the Full Check-then-Refine Loop Without Asking, Consult graphify-out/ Before Answering Codebase Questions, Isolate Variables Systematically When Debugging, Verify-then-Commit Loop (test, compose up, poll health, curl, log, commit), AGENTS.md project brief (stale mirror of CLAUDE.md), Research Platform (multi-agent research system), Known documentation discrepancies

### Community 20 - "Python Extractor Sidecar"
Cohesion: 0.36
Nodes (7): BaseModel, extract(), ExtractIn, ExtractOut, health(), get, post

### Community 21 - "Control Plane, SSE and Auth"
Cohesion: 0.25
Nodes (8): control-plane (port 8083), Deadline Sweeper, Postgres-counter Fan-in (not Kafka Streams windowing), SSE progress streaming, Where auth lives and where it does not, Spring Security + JWT on control-plane Only, SSE Page (plain HTML), Rate Limiting — 3 Layers, 3 Threats

### Community 22 - "Infrastructure Containers and Schema"
Cohesion: 0.25
Nodes (8): postgres service (pgvector/pgvector:pg16), redpanda service (Kafka API, dual listener), Database schema: runs / dag_nodes / dag_levels, Kafka topics (research.subtasks / research.findings / agent.events), Run lifecycle (10 steps, submit to deliver), Whole-system architecture diagram (built vs planned), Beginner-friendly project guide, Research Platform guide (PDF render)

### Community 23 - "Build Traps and Error Handling"
Cohesion: 0.25
Nodes (8): agent-service (port 8082, blueprint), Suspicious: java.version never wired to the compiler, Maven multi-module aggregator build, Redpanda (Kafka-compatible broker), No error handling anywhere in the search flow, Crash loudly on a corrupt counter, Reading the gauge never burns fuel, Check Yourself (15 questions)

### Community 24 - "Project Premise and Local Stack"
Cohesion: 0.25
Nodes (8): Docker + docker-compose (5 containers), Problem: search ranks, it never verifies, retrieval-service (port 8081), SearXNG metasearch container, Spring Boot 4.1.0 + embedded Tomcat, Two ignition switches (compose up, then the Java apps), Plain-text diagrams instead of Mermaid, The Story of This Codebase (guide)

### Community 25 - "Sidecar Concurrency and Deps"
Cohesion: 0.29
Nodes (7): Parallelize Independent Chunks via Subagents, extractor sidecar (Python/trafilatura), extractor service (build: ./extractor), Semaphore(4) in Front of the Extractor Sidecar, Parallel URL Fetching with CompletableFuture, fastapi dependency, trafilatura dependency

### Community 26 - "Quota Boundary and Rate Limiting"
Cohesion: 0.33
Nodes (7): Retrieval is the Quota Boundary, Atomic check-then-act in Redis via Lua, Cache Stampede / Single-Flight Lock (thundering herd), Extractor Sidecar Status Enum Deviation (OK | PAYWALLED only), Quota Enforcement (remaining() <= 0 → 429 before spending), Per-domain Token Bucket as Redis Lua Script, quota.daily-limit config

### Community 28 - "Cross-language Field Naming"
Cohesion: 0.40
Nodes (5): Jackson 3 (tools.jackson.*), content renamed to snippet at the API boundary, Record field names ARE the public contract, camelCase publishedAt across the language wall, pydantic validation (HTTP 422 before our code)

### Community 29 - "Working Agreement and Conventions"
Cohesion: 0.50
Nodes (4): Documentation Quality Bar (accurate + layman-readable), Documentation Tasks Must Not Touch Source Code, Coding Conventions (records, RestClient, Flyway-first, constructor injection), Working Agreement (who writes what)

### Community 30 - "Explicitly Cut Scope"
Cohesion: 0.67
Nodes (3): Explicitly Cut Scope (weeks 1-4), Deliberately Not Built (weeks 1-4), Accepted Risk: Scraped Text into LLM Prompt Unfiltered

### Community 31 - "Health Checks and Binding"
Cohesion: 0.67
Nodes (3): Actuator /actuator/health, Healthcheck as a Python one-liner, --host 0.0.0.0 vs loopback

## Ambiguous Edges - Review These
- `AGENTS.md project brief (stale mirror of CLAUDE.md)` → `Known documentation discrepancies`  [AMBIGUOUS]
  AGENTS.md · relation: conceptually_related_to

## Knowledge Gaps
- **38 isolated node(s):** `agent-service`, `common`, `control-plane`, `com.comeback.researchplatform:research-platform`, `retrieval-service` (+33 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **23 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `AGENTS.md project brief (stale mirror of CLAUDE.md)` and `Known documentation discrepancies`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `retrieval-service (port 8081)` connect `Retrieval Endpoints and Fan-out` to `Architecture Decisions and Contracts`, `Agent Roles and Kafka Config`, `Workflow Preferences and Docs Audit`, `Infrastructure Containers and Schema`, `Sidecar Concurrency and Deps`, `Quota Boundary and Rate Limiting`?**
  _High betweenness centrality (0.028) - this node is a cross-community bridge._
- **Why does `Research Platform (multi-agent research system)` connect `Workflow Preferences and Docs Audit` to `Architecture Decisions and Contracts`, `Verification, Critic and Evals`, `Retrieval Endpoints and Fan-out`, `Week Plan and Kubernetes Phase`, `Agent Roles and Kafka Config`, `Control Plane, SSE and Auth`?**
  _High betweenness centrality (0.028) - this node is a cross-community bridge._
- **Why does `Whole-system architecture diagram (built vs planned)` connect `Infrastructure Containers and Schema` to `Retrieval Endpoints and Fan-out`, `Control Plane, SSE and Auth`, `Agent Roles and Kafka Config`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **What connects `agent-service`, `common`, `control-plane` to the rest of the system?**
  _38 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Spring Wiring and Config Beans` be split into smaller, more focused modules?**
  _Cohesion score 0.10638297872340426 - nodes in this community are weakly interconnected._
- **Should `Test Suite and Boot Contexts` be split into smaller, more focused modules?**
  _Cohesion score 0.09302325581395349 - nodes in this community are weakly interconnected._