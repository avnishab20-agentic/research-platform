# 2026-09-22 — the researcher loop works, live, end to end

This is the milestone entry. Everything built in the last three sessions
(common's records, Kafka wiring, the Postgres schema, the DeepSeek swap,
the RAG utility) came together today into one real, working thing: a
question goes in, a correct, sourced, confidence-scored answer comes out.

## What got built

**`agent-service/.../retrieval/`** — a small HTTP client so `agent-service`
can call `retrieval-service` the way `CLAUDE.md` requires ("agents ->
retrieval-service = HTTP", not Kafka — Kafka is for the long/durable hops,
this is a cache lookup that should come back in well under a second).
`RetrievalClient.search(...)` and `.extract(...)` wrap two `POST` calls.
The request/response shapes (`SearchRequest`, `SearchResult`, `ExtractedDocument`,
etc.) are a second, independent copy of `retrieval-service`'s own DTOs, not
a shared import — deliberately: refactoring `retrieval-service`'s existing,
tested DTOs into the shared `common` module was judged out of scope for
today given the deadline, and Jackson only cares that the JSON *shape*
matches, not that both sides use the same Java class. Named `ExtractedDocument`
rather than `Document` specifically to avoid colliding with Spring AI's own
`Document` class, which the RAG code (next section) uses right next to it.

**`agent-service/.../rag/PassageStore.java`** — the shared chunk/embed/retrieve
utility from yesterday's RAG decision, now actually written:
- `index(runId, sourceUrl, text)` — splits text on blank lines into
  paragraph-sized chunks (hard-splitting anything still too long), wraps
  each as a Spring AI `Document` tagged with `runId` and `sourceUrl` in its
  metadata, and hands the list to `VectorStore.add(...)`, which embeds and
  stores them.
- `retrieveTopK(runId, query, k)` — embeds the query, asks the vector store
  for the `k` closest stored chunks, filtered down to only this `runId`'s
  chunks (via Spring AI's `FilterExpressionBuilder`) so one research run
  can never accidentally retrieve another run's material.
- Marked with a `ponytail:` comment on the chunking: it's a naive
  char-count splitter, not sentence- or token-aware. Flagged as the
  corner deliberately cut, with the upgrade path named, rather than
  silently shipped as if it were a considered choice.

**`agent-service/.../researcher/ResearcherService.java`** — the actual
8-step loop from `docs/PLAN.md`, one method per step:
1. `generateQueries` — asks DeepSeek for 3-5 search queries for the
   sub-question, one per line (kept as plain text rather than asking for
   structured JSON output — simpler to parse reliably, and query
   generation is low-stakes enough that a malformed response just means
   "try fewer queries," not a wrong fact reaching a reader).
2. `search` — calls `RetrievalClient.search(...)`, dedupes results by URL
   across all the queries (two different queries often surface the same
   article).
3. *(tier filtering happens server-side in `retrieval-service`, via the
   `minTier` request field — nothing extra needed here.)*
4. `extractTopDocuments` — extracts the top N deduped URLs, keeps only
   documents that came back `status: "OK"` with real text (silently
   dropping `PAYWALLED`/`UNREACHABLE`/`TOO_LARGE`/`RATE_LIMITED` — a
   researcher shouldn't stall on one bad source when four others are fine).
5. RAG — indexes every usable document's text into `PassageStore`, then
   retrieves the passages closest to the sub-question itself.
6. `synthesizeAnswer` — asks DeepSeek to answer *using only the retrieved
   passages*, explicitly instructed not to use outside knowledge and to
   say so plainly if the passages don't actually answer the question
   (rather than guessing) — this is the sentence in the prompt doing the
   most work against fabrication.
7. Confidence — **not** a second LLM call. Computed in plain code from the
   best (lowest-numbered) tier actually cited: tier 1 sources -> 0.95
   confidence, tier 2 -> 0.85, tier 3 -> 0.6, tier 4 or nothing usable ->
   0.4. Matches PLAN's "downgrade if only tier 3-4 support" without
   spending a call asking the model to grade its own work.
8. Emits a `ResearchFinding` — `COMPLETE` if everything ran clean,
   `PARTIAL` at any point a budget was breached or a step came back empty
   (never silently dropped, per `CLAUDE.md`'s rule on failed runs).

**Budget enforcement**, PLAN's "90s wall clock, 25k tokens": wall clock is
checked after every step via `Instant.now()` comparison; tokens are summed
from each `ChatResponse`'s usage metadata (which, per yesterday's live
test, includes DeepSeek's reasoning tokens — a reasoning model can spend a
real chunk of budget "thinking" before it writes anything visible, so this
had to count that too, not just the visible answer length). Either budget
being exceeded short-circuits the loop straight to a `PARTIAL` finding
with whatever was gathered so far, rather than pushing on and quietly
going over.

**Explicitly not the final version:** `ResearcherProperties` (wall-clock
budget, token budget, query/document/passage counts) is a small local
config record, not yet PLAN's shared `guardrails:` tree bound to a record
in `common/` (that's still guardrail build-order item #1, not done). Kept
config-driven rather than hardcoded specifically so folding it into the
real tree later is a move, not a rewrite — said so directly in the class's
javadoc.

**Kafka wiring:** `ResearchSubtaskListener` consumes one `ResearchSubtask`
at a time from `research.subtasks` (`max-poll-records: 1`, `concurrency: 6`
— up to 6 sub-questions researched in parallel, PLAN's Week 2 number),
runs the loop, and publishes the resulting `ResearchFinding` to
`research.findings`, keyed by `runId` so one run's findings land
co-located on one partition.

## Two real bugs found by actually running it (not by reading the code)

**Bug 1 — wrong RestClient timeout API.** First draft of
`RetrievalClientConfig` used a `ClientHttpRequestFactorySettings` builder
class that doesn't exist at that package path in this Spring Boot version
— same *family* of "the framework moved this since the library/docs I
half-remembered were written" issue as the Spring AI version mismatch
yesterday. Fixed by copying the pattern `retrieval-service` already uses
successfully elsewhere (`ExtractClientConfig`): a plain
`JdkClientHttpRequestFactory` with an explicit `HttpClient`, timeouts set
directly on it. Reusing an already-proven pattern in the same codebase
instead of guessing at a newer API surface — worth remembering as a
general move.

**Bug 2 — the real one, only found by actually publishing a message.**
Everything compiled clean and the app started clean. The first live test
(publishing a hand-crafted `ResearchSubtask` JSON message directly via
`rpk topic produce`) failed immediately with
`MessageConversionException: Cannot convert from [java.lang.String] to
[...ResearchSubtask]`. Root cause, once traced: `application.yml` had
`spring.kafka.producer.value-serializer` set to Spring's `JsonSerializer`
— but never actually set `spring.kafka.consumer.value-deserializer` to the
matching `JsonDeserializer`. Spring Boot's default consumer deserializer
is a plain `StringDeserializer`, so every message was arriving as a raw
`String`, and Spring's `@KafkaListener` argument-conversion machinery had
no way to turn a `String` into a `ResearchSubtask` — it isn't a JSON step
at that layer, just a generic (and here, impossible) type conversion.

All the `spring.json.trusted.packages`/`value.default.type` properties I'd
already written were sitting there completely inert the whole time,
configuring a deserializer that was never actually selected. Nothing
caught this at compile time or at `contextLoads` — it only surfaces the
moment a real message hits the topic, which is exactly why this got
caught today rather than staying hidden: the plan was always to publish a
real test message and watch it work end to end, not to trust that a green
build meant a working pipeline.

**Fix:** two explicit lines —
```yaml
spring.kafka.consumer.key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
```
Also added (while already in there): `spring.json.use.type.headers: false`
plus a pinned `spring.json.value.default.type`, so this consumer works
identically whether the message came from a real Spring producer (which
stamps a `__TypeId__` header automatically) or from a hand-crafted test
message with no header at all — `research.subtasks` only ever carries one
message type, so there's no reason to depend on the header being present.

## The actual live test

Published a real sub-question onto `research.subtasks`:
> "What is the current repo rate set by the Reserve Bank of India?"

45 seconds later, this landed on `research.findings`:
```json
{
  "subQuestion": "What is the current repo rate set by the Reserve Bank of India?",
  "answer": "According to the provided RBI National Summary Data Page dated
    Sep 18, 2026, the Policy Repo Rate for the latest listed week ended
    Sep 11, 2026 is 5.25 per cent.",
  "sources": [
    {"url": "https://rbi.org.in/Scripts/BS_NSDPDisplay.aspx?param=4", "tier": 1},
    {"url": "https://m.rbi.org.in/SCRIPTS/PublicationsView.aspx?id=23865", "tier": 3},
    {"url": "https://m.rbi.org.in/Scripts/BS_PressreleaseDisplay.aspx", "tier": 3}
  ],
  "confidence": 0.95,
  "status": "COMPLETE"
}
```
Correct, current, cited a tier-1 government source, and the confidence
score (0.95) correctly reflects that a tier-1 source backed the answer.
Every layer of the stack — DeepSeek query generation, SearXNG search via
`retrieval-service`, real HTML extraction, real embedding + pgvector
storage + retrieval, DeepSeek synthesis grounded in only the retrieved
passages — ran for real in that one 45-second window.

Ran the full test suite afterward across all four modules: **70/70 green**
(`common` 0 tests, no logic to test yet; `retrieval-service` 67;
`agent-service` 1; `control-plane` 1 — the low counts on `agent-service`/
`control-plane` are the existing `contextLoads` boilerplate only;
`ResearcherService`/`PassageStore`/`RetrievalClient` have no unit tests
yet, which is honestly the next debt to pay down before this goes much
further).

## What this proves, and what it doesn't yet

**Proven live:** the entire Week 2 "done when" — a sub-question goes in
via Kafka, gets researched using real search + real RAG + a real LLM, and
a correctly-sourced finding comes out — for one sub-question.

**Not yet proven:** PLAN's actual Week 2 done-when is "6 researchers run
in parallel, report assembles." Today only ran one sub-question through
one consumer thread. Still missing: the planner (something that takes a
top-level question and emits 6-8 `ResearchSubtask` messages), the fan-in
counter (`dag_levels` `UPDATE ... RETURNING`, one consumer noticing
`completed == expected`), and anything that actually assembles the
findings into a report. `ResearcherService` has no unit tests of its own
yet — everything above was verified by one live run, which proves it
*can* work, not that it reliably *does* across edge cases (a dead
retrieval-service, an empty search result, a DeepSeek timeout).

## Next

Planner (in `control-plane`, emits the flat 6-8-question fan-out) and the
fan-in counter are the two pieces standing between here and PLAN's actual
Week 2 done-when. Unit tests for `ResearcherService` are also overdue —
today's single live run is real evidence but not a regression net.
