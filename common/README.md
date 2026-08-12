# common

## What this is
A shared library — plain Java `record`s that describe the shape of data moving
between the three services (Kafka messages, HTTP payloads, agent contracts like
`Claim` or `ResearchFinding`). It is **not** a service: no `main` method, nothing
here ever runs on its own.

## How it works
It's a plain Maven `jar` module. The other three services (`retrieval-service`,
`agent-service`, `control-plane`) declare it as a dependency and get these types
on their classpath at compile time. It deliberately does **not** have the
`spring-boot-maven-plugin` — that plugin repackages a module into an executable
"fat jar" meant to be run, which would make no sense for a module that's only
ever imported, never launched.

## Why it exists
Three services need to agree on exactly what a message looks like — e.g. what
fields a `Claim` has, what type `sourceId` is. If each service defined its own
version of that type, a typo or type mismatch (say, one service uses `String`
for an ID and another uses `UUID`) would only be caught when a real message
fails to deserialize in production — usually confusing, usually at 2am.

By putting the type in one shared module that every service imports, the same
kind of mistake becomes a **compile error** on `mvn install`, days earlier and
with a stack trace pointing at the exact line.

## Why only this — the boundary
`common` holds shared **data shapes only** — no business logic, no Spring
annotations, no database or Kafka client code. The moment something in here
needs a `@Service` or a `JdbcTemplate`, it belongs in a real service instead.
Keeping it dumb-and-dependency-free is what lets every other module safely
depend on it without dragging in Spring, Kafka, or JPA transitively.

## Status
Empty. No records exist yet — they'll get added once `agent-service` and
`control-plane` are far enough along to need a shared `Claim`/`ResearchFinding`
contract between them (Week 2–3 per `docs/PLAN.md`).
