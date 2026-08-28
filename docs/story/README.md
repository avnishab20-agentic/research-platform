# The Story of This Codebase

A narrative guide to this repo, written for someone who can read the code but wants to
understand what happens at runtime, why each piece exists, and how control moves
between classes. Built from a full read of every source file — every factual claim
carries a file:line citation. Nothing here was invented; gaps are marked
`COULDN'T TRACE`.

---

## Table of Contents

**[00 — The World](00-the-world.md)** — the problem, every stack piece & library, the full cast of classes
- [The problem this project solves](00-the-world.md#the-problem-this-project-solves)
- [The World (stack + libraries, what breaks if each vanished)](00-the-world.md#the-world)
- [The Cast (every class as a character, by layer)](00-the-world.md#the-cast)

**[01 — The Search Flow](01-search-flow.md)** — the flagship: HTTP → Redis cache-aside → SearXNG → tiering → JSON
- [1. What triggers this](01-search-flow.md#1-what-triggers-this) · [2. Diagram](01-search-flow.md#2-the-whole-journey-as-a-diagram) · [3. Narration, step by step](01-search-flow.md#3-the-narration-step-by-step)
- [4. Framework magic roundup](01-search-flow.md#4-framework-magic-roundup-who-calls-what-unseen) · [5. Unhappy paths](01-search-flow.md#5-unhappy-paths-when-it-breaks-where-and-what-state-remains) · [6. If I changed X, what breaks](01-search-flow.md#6-if-i-changed-x-what-breaks)

**[02 — The Quota Flow](02-quota-flow.md)** — reading the daily credit meter; why "midnight reset" is an illusion
- [1. What triggers this](02-quota-flow.md#1-what-triggers-this) · [2. Diagram](02-quota-flow.md#2-the-journey-as-a-diagram) · [3. Narration](02-quota-flow.md#3-the-narration)
- [4. Framework magic roundup](02-quota-flow.md#4-framework-magic-roundup) · [5. Unhappy paths](02-quota-flow.md#5-unhappy-paths) · [6. If I changed X, what breaks](02-quota-flow.md#6-if-i-changed-x-what-breaks)

**[03 — The Extract Stub Flow](03-extract-stub-flow.md)** — the deliberately empty endpoint and the unemployed pieces beneath it
- [1. What triggers this](03-extract-stub-flow.md#1-what-triggers-this) · [2. Diagram](03-extract-stub-flow.md#2-the-journey-as-a-diagram) · [3. Narration](03-extract-stub-flow.md#3-the-narration)
- [4. Framework magic roundup](03-extract-stub-flow.md#4-framework-magic-roundup) · [5. Unhappy paths](03-extract-stub-flow.md#5-unhappy-paths) · [6. If I changed X, what breaks](03-extract-stub-flow.md#6-if-i-changed-x-what-breaks)

**[04 — The Extractor Sidecar Flow](04-sidecar-extraction-flow.md)** — the Python service end to end: uvicorn, FastAPI, pydantic, trafilatura
- [1. What triggers this](04-sidecar-extraction-flow.md#1-what-triggers-this) · [2. Diagram](04-sidecar-extraction-flow.md#2-the-journey-as-a-diagram) · [3. Narration](04-sidecar-extraction-flow.md#3-the-narration)
- [4. Framework magic roundup](04-sidecar-extraction-flow.md#4-framework-magic-roundup) · [5. Unhappy paths](04-sidecar-extraction-flow.md#5-unhappy-paths) · [6. If I changed X, what breaks](04-sidecar-extraction-flow.md#6-if-i-changed-x-what-breaks)

**[05 — Boot & Compose](05-boot-and-compose.md)** — how everything wakes up; where all the framework magic actually happens
- [1. What triggers this](05-boot-and-compose.md#1-what-triggers-this) · [2. Wake-up diagram](05-boot-and-compose.md#2-the-wake-up-as-a-diagram) · [3. Narration](05-boot-and-compose.md#3-the-narration)
- [4. Framework magic roundup](05-boot-and-compose.md#4-framework-magic-roundup-the-whole-cast-of-invisible-actors) · [5. Unhappy paths at boot](05-boot-and-compose.md#5-unhappy-paths-at-boot) · [6. If I changed X, what breaks](05-boot-and-compose.md#6-if-i-changed-x-what-breaks)

**[90 — Method Reference](90-method-reference.md)** — every non-trivial method: purpose, callers/callees, side effects, failure modes
- [RetrievalController](90-method-reference.md#webretrievalcontrollerjava-the-front-door) · [SearchService](90-method-reference.md#searchsearchservicejava-the-librarian) · [QuotaService](90-method-reference.md#quotaquotaservicejava-the-meter) · [SourceTierResolver](90-method-reference.md#tiersourcetierresolverjava-the-grader)
- [UrlNormalizer](90-method-reference.md#urlurlnormalizerjava-the-alias-detective) · [Hashing](90-method-reference.md#hashhashingjava-the-fingerprint-clerk) · [ExtractCacheKey](90-method-reference.md#extractextractcachekeyjava-the-label-maker) · [ExtractorClient](90-method-reference.md#extractextractorclientjava-the-unemployed-courier)
- [Config factories](90-method-reference.md#config-factories-each-one-bean-method-called-by-spring-at-boot) · [Python sidecar](90-method-reference.md#extractormainpy-the-python-cast) · [Test entry points](90-method-reference.md#test-entry-points-who-runs-them-maven-surefire-mvn-test)

**[91 — Glossary](91-glossary.md)** — every term, annotation, pattern, acronym; 🧱 marks blueprint-only concepts

**[92 — Check Yourself](92-check-yourself.md)** — 15 questions (incl. trace-the-path) with collapsed answers

---

**Scope note:** flows 1–6 are real code narrated as it runs today. The full agent
pipeline (planner → Kafka fan-out → RESEARCHER/WRITER/CRITIC → verified report → SSE)
exists only as blueprint in `docs/PLAN.md` / `CLAUDE.md`; the guide points at its
already-built props (tables, topics, `common` slot) without inventing unbuilt code.

*Generated 2026-08-23 against commit `6955d9f`. If code changes, line citations age —
re-run the recon before trusting numbers blindly.*
