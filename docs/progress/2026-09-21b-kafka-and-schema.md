# 2026-09-21 (part 2) — Kafka wiring, Week 2/3 database schema, a real version bug

Second entry of the day. Picks up right after the `common` module and RAG
decision from part 1.

---

## 1. Kafka: topic names and provisioning code

**New file:** `common/.../KafkaTopics.java` — three constants
(`RESEARCH_SUBTASKS`, `RESEARCH_FINDINGS`, `AGENT_EVENTS`) so every service
that reads or writes a topic name does it through one shared source instead
of typing the string `"research.subtasks"` in three different files, where
a typo in just one of them would silently create a fourth, empty topic no
one is listening to.

**New file:** `control-plane/.../config/KafkaTopicConfig.java` — three
`NewTopic` beans (`research.subtasks` 12 partitions, `research.findings` 6,
`agent.events` 3 — the exact numbers from `docs/PLAN.md` Session 1).

### Why this needed writing at all

Back in Session 3, the topics were created once by hand, by running
`rpk topic create` directly inside the Redpanda container. That worked for
that session, but I checked today and the topics were gone — `docker-compose.yml`
never gave Redpanda a persisted volume (unlike Postgres, which has
`postgres_data:`), so every container restart wipes it back to empty. A
manual one-time command isn't something a fresh clone of this repo can
reproduce.

The fix: `NewTopic` beans. Spring Kafka's `KafkaAdmin` sees any bean of
type `NewTopic` in the application context and creates it on startup if it
doesn't already exist — so from now on, `docker compose up` +
`mvn -pl control-plane spring-boot:run` recreates the topics automatically,
every time, with no manual step. `control-plane` owns this (not
`agent-service`) because it's the traffic-cop service — the one already
described in its own README as owning "planner, fan-out, fan-in, budget."

**Verified live:** started `control-plane`, then ran
`docker exec research-redpanda rpk topic list` — all three topics present
with the correct partition counts.

---

## 2. The Kafka consumer config — and one deliberate deviation from PLAN's exact syntax

**Files:** `agent-service/application.yml`, `control-plane/application.yml`
(both services had a bare `application.properties` before today — one line
each, `spring.application.name` and `server.port`. Converted to `.yml` for
the same reason `retrieval-service` already uses `.yml`: nested config like
Kafka's is unreadable as flat dotted properties.)

`docs/PLAN.md` gives this exact block:
```yaml
spring.threads.virtual.enabled: true
spring.kafka.listener.concurrency: 6
spring.kafka.consumer.max-poll-records: 1
spring.kafka.consumer.max-poll-interval-ms: 600000
```

I kept the first three lines verbatim. For the fourth
(`max-poll-interval-ms`), I moved it into Spring Kafka's raw-properties
escape hatch instead:
```yaml
spring.kafka.consumer.properties.max.poll.interval.ms: 600000
```

**Why the change:** Spring Boot only auto-binds YAML keys to Java fields it
actually has a field for. If `max-poll-interval-ms` isn't one of the
handful of consumer settings Spring Boot exposes as a first-class property
(`max-poll-records` is; I couldn't confirm the interval one is, for this
exact Spring Boot version), the YAML key gets silently accepted and
silently ignored — no error, no warning, just a setting that does nothing.
That exact failure mode is already documented in this project's own
`CLAUDE.md`, from Session 14: *"An unbound YAML key doesn't error — Spring
just ignores it."* The `properties.*` map is different: it's Spring Kafka's
guaranteed pass-through straight to the underlying Kafka client's raw
config, unaffected by which specific settings Spring Boot has decided to
expose as named fields in any given version. Slightly more verbose, but it
can't silently do nothing.

**Why this setting exists at all** (from `CLAUDE.md`'s own trap list): the
Kafka client's default `max.poll.interval.ms` is 5 minutes. A researcher
processing one sub-question can legitimately take up to 90 seconds *per
step*, and the loop has several steps — comfortably past 5 minutes is
possible. If the interval is exceeded, Kafka assumes the consumer died,
kicks it out of the consumer group, and reassigns its partition to another
consumer — which will then reprocess the same message, because the
original consumer's offset was never committed. Silent duplicate work, no
error anywhere. Setting this to 600000 (10 minutes) gives real headroom.

Also added: `auto-offset-reset: earliest`. Without this, a consumer group
that has never run before defaults to only seeing messages published
*after* it first connects — meaning a message published one second before
the consumer starts up would be invisible forever. `earliest` means "if
you've never committed an offset before, start from the very beginning of
the topic."

---

## 3. The Postgres schema for Week 2/3: `V2__research_and_verification.sql`

**File:** `control-plane/src/main/resources/db/migration/V2__research_and_verification.sql`

Four new tables, applied via Flyway (matching the project's "Flyway
migration first, then the entity to match — never `ddl-auto`" rule):

**`sources`** — one row per (research run, URL) pair actually fetched:
`url`, `tier`, `extracted_text`, `fetched_at`. Has a `UNIQUE (run_id, url)`
constraint — if two different sub-questions within the same run both cite
the same article, it's stored once, not twice.

**`claims`** — one row per `Claim` (see part 1's `common` records): the
text, which `source_id` backs it up, its `kind`
(FACT/FIGURE/QUOTE/INFERENCE), and a Postgres native array column
(`corroborating UUID[]`) for any other claim IDs that say the same thing
from a different source.

**`claim_verdicts`** — one row per claim, once the Critic has graded it:
the verdict (SUPPORTED/PARTIAL/UNSUPPORTED/CONTRADICTED/UNREACHABLE) and
the `evidence_passage` — the actual sentence from the source that the
Critic matched against, not just its opinion.

**`vector_store`** — this is the RAG piece from part 1. Its exact shape
(`id`, `content`, `metadata` as JSON, `embedding` as a `VECTOR(384)`
column) matches what Spring AI's `PgVectorStore` class expects to find, so
that library can read and write it directly. I wrote this table by hand in
the migration, with Spring AI's own automatic schema creation turned off
(`spring.ai.vectorstore.pgvector.initialize-schema: false` in
`agent-service/application.yml`) — same reasoning as the Flyway-first rule
elsewhere in the project: one thing owns the database schema, and it's
never a library's "convenient" auto-setup.

`384` is not an arbitrary number — it's the exact size of the vector that
the local `all-MiniLM-L6-v2` embedding model (part 1's RAG decision)
produces for any input. If that embedding model is ever swapped for a
different one, this column's width has to change to match, or every
insert will fail. Left a comment on that line saying so.

Also added an index:
```sql
CREATE INDEX ON vector_store USING HNSW (embedding vector_cosine_ops);
```
Without an index, "find the most similar vector" means comparing the query
against *every single row* in the table — fine at 50 rows, unusable at
50,000. `HNSW` (Hierarchical Navigable Small World) is the standard
approximate-nearest-neighbor index type for this — it trades a small,
usually negligible amount of search accuracy for a huge speed win as the
table grows. `vector_cosine_ops` tells Postgres which distance function to
use with it — cosine similarity, matching the reasoning in
`docs/LEARNING.md`'s new RAG section about why cosine (angle, not raw
magnitude) is the right measure for comparing meaning.

**Verified live:** started `control-plane` against the real Postgres
container, then queried `\dt` (all four tables present) and
`flyway_schema_history` directly (`V2 | research and verification | t` —
applied successfully).

---

## 4. A real version-compatibility bug, and how it got found

While running `agent-service`'s tests for the first time (just the
boilerplate `contextLoads` test Spring generates by default — no logic of
mine yet), the whole application context failed to start:

```
Caused by: java.lang.ClassNotFoundException:
  org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
```

**What this means, unpacked:** Spring AI's own internal setup code (its
"autoconfiguration" — the part that wires up its beans automatically) was
compiled expecting a specific class to exist at a specific package path in
Spring Boot. That class doesn't exist at that path anymore. This is the
exact same *category* of bug this project already hit once before, back
in Session 3 — Spring Boot 4.x reorganized a lot of its internals into
separate per-feature modules and packages compared to Boot 3.x, and any
library built against the older layout breaks in a confusing way when
paired with 4.x. Session 3's version was `flyway-core` silently not
triggering Flyway at all; this one is a loud crash instead, which is
actually easier to debug.

**Root cause:** I'd picked Spring AI version `1.0.3` — the newest version
docs referred to as GA at the time — but that release predates Spring Boot
4.1 (which this project deliberately uses, per `CLAUDE.md`'s session
notes: "Boot **4.1.0** GA"). Spring AI's `1.0.x` line was built and tested
against Spring Boot 3.x's package layout.

**The fix:** checked which newer Spring AI releases actually exist and
resolve (probed the real Maven repository rather than guessing), and found
`2.0.0` — its major version bump lines up with Spring Boot's own 4.x major
bump, which is usually a strong signal of "this is the version meant to
pair with the new Boot line." Swapped `<spring-ai.version>` from `1.0.3`
to `2.0.0` in `agent-service/pom.xml`. Re-ran the test: the whole
application context started cleanly this time — and along the way, it
downloaded the local embedding model for the first time (~90MB, one-time,
cached after) and connected to the real `vector_store` table this session
had just created, logging `Is empty: false` — confirming Spring AI's
`PgVectorStore` correctly recognized the hand-written Flyway table as its
own.

**Why this is worth remembering as a pattern, not just a fix:** the
project is deliberately running on very new major versions (Spring Boot
4.1, released not long before this project started) precisely because
that's what's current — but "current" for a framework and "current" for
every library built on top of it don't always move in lockstep. The
general move, any time an autoconfiguration class fails to load with
`ClassNotFoundException` or `NoClassDefFoundError`: check whether the
*library's* version actually claims support for the *framework* version in
use, rather than assuming the library itself is broken.

---

## What's still blocking

Same as part 1 — an `ANTHROPIC_API_KEY` in your shell environment. Kafka
config, the database schema, and the RAG plumbing (embedding model +
vector store) are now all wired and verified live. What's left before any
of it does real work: the researcher loop itself, the planner, the fan-in
counter, and the Critic — all of which need to actually call Claude.
