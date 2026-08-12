# agent-service

**Port:** 8082

## What this is
The actual "AI workers" of the system. There are three roles:

- **RESEARCHER** — takes one sub-question, searches and reads sources (via
  `retrieval-service`), and writes up a finding with citations.
- **WRITER** — takes all the researchers' findings and assembles them into a
  structured report made of `Claim`s (not free-form prose — see below).
- **CRITIC** — re-checks each claim against its cited source and grades it
  (`SUPPORTED` / `UNSUPPORTED` / `CONTRADICTED` / etc).

All three roles live in this **one** Spring Boot application, switched on via
**Spring profiles** — not three separate services.

## How it will work
Each role is a Kafka **consumer** listening on a topic
(e.g. `research.subtasks` for RESEARCHER). When a message arrives, the active
profile's logic runs: call an LLM (Haiku for cheap extraction/grading work,
Sonnet for the harder synthesis step), call `retrieval-service` over plain
HTTP for any search/fetch it needs, and publish a result back to Kafka
(or, for the fan-in step, update a counter in Postgres).

## Why one service with profiles, not three
At this project's scale — one person, ~20 hrs/week, running on a laptop — three
separate deployables would mean three sets of Kafka consumer config, three
Docker images, three things that can independently misbehave, for logic that
mostly differs in **which prompt runs**, not in infrastructure needs. A Spring
profile is a single config value (`spring.profiles.active=researcher`) that
picks which `@Service` beans get loaded. Same jar, same `docker-compose`
entry (deployed multiple times with a different profile each), far less to
maintain.

This is a real interview-relevant tradeoff, not just laziness: the moment
these roles need genuinely different scaling behavior, different SLAs, or
different teams owning them, splitting into real services would make sense.
None of those are true here yet.

## Why only this — the boundary
Agents never call a search API directly — always through `retrieval-service`,
which owns caching and rate limiting. Agents also never write raw prose with
footnotes: the WRITER emits structured `Claim`s (each tied to exactly one
`sourceId`), because free-form prose with citations sprinkled in makes it
practically impossible for the CRITIC to programmatically check "is this
specific sentence actually supported by that specific source."

## Status
Skeleton only — the Spring Boot app boots and `/actuator/health` responds, but
no Kafka consumers or agent logic exist yet. That starts Week 2 per
`docs/PLAN.md` (the researcher loop first, then writer/critic in Week 3).
