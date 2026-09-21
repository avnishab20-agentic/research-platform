# 2026-09-21 — Week 1 closed, Week 2 kicked off, RAG added to the plan

This is the first entry in `docs/progress/`. From today, Claude is writing
nearly all the code (see `CLAUDE.md`'s working agreement — that flipped this
week because of a real deadline: finish the 4-week PLAN by end of this week).
This file exists so you can read back later, at your own pace, exactly what
was built, why, and what it means — even though you didn't type it.

Every file from now on in this folder covers one session. Read them in order
if you want the story from the start.

---

## 1. Closing Week 1: the `Semaphore(4)` gate

**File touched:** `retrieval-service/.../extract/ExtractService.java` (+
`ExtractProperties.java`, `application.yml`, two test files).

### The problem

A few sessions back, `ExtractService.extract()` was rewritten to fetch every
URL in a batch **in parallel**, one lightweight "virtual thread" per URL.
Virtual threads are cheap — you can spin up hundreds without much cost,
unlike old-school OS threads. Great for fetching web pages.

But each of those threads, once it has the raw HTML, also calls a separate
little Python service (`extractor/`, running a library called trafilatura)
to pull the clean article text out of the HTML noise (nav bars, ads, etc.).
That Python service runs as **one single process**. It can handle maybe 4
requests at once comfortably; hit it with 50 at once and it either falls
over or gets so slow it might as well have.

So: fetching HTML = fine to do wide open. Calling the Python extractor =
needs a hard cap.

### The fix: a `Semaphore`

Think of a nightclub bouncer holding exactly 4 wristbands. A thread that
wants to call the Python extractor has to get a wristband first
(`.acquire()`). If all 4 are already out, it waits in line. When it's done,
it gives the wristband back (`.release()`), and whoever's next in line gets
it.

```java
private final Semaphore extractorPermits;
// ...
this.extractorPermits = new Semaphore(props.maxConcurrentExtractions());
```

It's wrapped **only** around the one line that calls the Python service —
everything else (cache reads, HTML fetching) stays fully parallel.

One important detail: the "give the wristband back" line sits inside a
`finally` block:

```java
try {
    extractorPermits.acquire();
    try {
        result = extractorClient.extract(url, page.html());
    } finally {
        extractorPermits.release();   // always runs, even if extract() throws
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return new Document(url, null, sourceTierResolver.resolveTier(url), "UNREACHABLE");
} catch (Exception e) {
    ...
}
```

Without the `finally`, a string of failed calls to the Python service could
slowly "leak" wristbands — hand them out, never get them back — until zero
were left and the whole service silently seized up forever. `finally` is
Java's guarantee: "this code runs whether the block above succeeded or
threw."

The `InterruptedException` gets caught **separately, before** the generic
`catch (Exception e)`, because if the app is shutting down and interrupts
this thread mid-wait, we want to notice that ("restore the interrupt flag
and bail"), not let the generic `catch` silently swallow it as if it were
just another failed extraction.

**Config added** (one new field, `maxConcurrentExtractions`, set to `4` in
`application.yml`) rather than a hardcoded `4` in the Java — matches the
project's rule that every limit is a config value, not a magic number.

**Verified:** ran the full suite — 67/67 green, no regressions.

---

## 2. The one-line bug that had been waiting weeks to bite

**File touched:** root `pom.xml` (the top-level Maven config all 4 services
inherit from).

### What "Maven" and "pom.xml" mean, quickly

Maven is the tool that builds this Java project — compiles the code, runs
tests, packages it. `pom.xml` is its config file. This project has a *root*
`pom.xml` (shared settings) and one *child* `pom.xml` per service
(`common`, `retrieval-service`, `agent-service`, `control-plane`), each of
which says "I inherit from the root one."

### The bug

The root `pom.xml` had this:
```xml
<java.version>21</java.version>
```
That looks like it should mean "compile this project as Java 21." It
doesn't — it's just a label with no wire connected to it. The actual
setting the compiler reads is a different one, `maven.compiler.release`,
and nothing in the file was setting *that*. So the compiler was silently
falling back to a much older Java version (8) — old enough that it doesn't
understand `record`, a modern Java keyword (added in Java 16) for a simple,
compact data-holder class.

This had already been spotted weeks ago (see `docs/story/00-the-world`,
written back in Session 10) as a "ticking trap" — it just hadn't gone off
yet, because `retrieval-service` happened to have its *own* copy of the
Java-version setting (left over from when a setup wizard generated it), so
it was shielded from the bug by accident. `common`, `agent-service`, and
`control-plane` had no such shield.

### The fix

```xml
<properties>
    <java.version>21</java.version>
    <maven.compiler.release>${java.version}</maven.compiler.release>
</properties>
```

One added line — now the "21" label actually drives the compiler,
everywhere, permanently. I found this because it's exactly what broke when
I tried to add the first `record` to the `common` module (next section).

**Lazy-fix principle worth remembering:** the bug technically only broke
`common` today, but the fix went in the *shared root file*, not a
workaround in `common`'s own file — because `agent-service` and
`control-plane` had the identical latent bug and would've hit the same wall
the moment either of them got its first `record`.

---

## 3. `common` module: 10 new files — the shared "rulebook"

**Folder:** `common/src/main/java/com/comeback/researchplatform/common/`

Before today this module was empty — a placeholder jar with a README
promising it would eventually hold shared data shapes, once `agent-service`
and `control-plane` were far enough along to need them.

### Why a shared module at all

Three separate services (`retrieval-service`, `agent-service`,
`control-plane`) all need to agree on what a message looks like when it
passes between them — e.g. "a `Claim` has exactly these fields, of exactly
these types." If each service defined its own copy of that shape, a typo
or a mismatched field type (one service uses `String` for an ID, another
uses `UUID`) would only surface when a real message failed to parse in
production — the worst possible time to find out.

By putting the shape in one module that all three import, that same
mistake becomes a **compile error** the moment you run `mvn install` —
caught in seconds, with a line number, instead of hours or days later at
2am.

### What a Java `record` is

A `record` is Java's shorthand for "an immutable bag of named, typed
fields, with a constructor, getters, `equals()`, `hashCode()`, and
`toString()` all generated for you." No business logic belongs in these —
just shape.

### The 10 records

```java
public record ResearchSubtask(UUID runId, UUID nodeId, int level, String subQuestion) {}
```
One sub-question, packaged up and ready to hand to a researcher.
`runId` ties it back to the overall research run; `nodeId` is this
sub-question's own ID; `level` is always `0` this month (the project
deliberately isn't building "some sub-questions depend on others" — see
`CLAUDE.md`'s "Explicitly cut" list); `subQuestion` is the actual text.

```java
public record ResearchFinding(UUID runId, UUID nodeId, String subQuestion,
        String answer, List<SourceRef> sources, double confidence, String status) {}
```
What a researcher hands back after working on one sub-question. `status`
is `COMPLETE` or `PARTIAL` — if the researcher ran out of its time/token
budget, it still emits *something* rather than nothing. "A failed run is
published, never silently dropped" is a rule stated explicitly in
`CLAUDE.md`.

```java
public record SourceRef(String url, int tier) {}
```
A small receipt: which URL, and how trustworthy (tier 1 = government site,
tier 4 = random blog, from Week 1's `SourceTierResolver`).

```java
public enum ClaimKind { FACT, FIGURE, QUOTE, INFERENCE }
```
A fixed label set. `FACT`/`FIGURE`/`QUOTE` get checked against a source by
the Critic later. `INFERENCE` means "this is the AI's own reasoning, not a
sourced fact" — it skips verification, but has to be visually marked as
such in the final report so a reader isn't misled into thinking it was
checked.

```java
public record Claim(UUID id, String text, UUID sourceId,
        List<UUID> corroborating, ClaimKind kind) {}
```
This is the single most important shape in the whole project. One
sentence, one attached source. The alternative — a flowing paragraph with
footnotes, like a normal article — is nearly impossible for software to
machine-verify, because you can't cleanly ask "which exact sentence does
footnote 3 support?" Forcing the Writer AI to output a *list* of these
small, separately-checkable objects is what makes the whole verification
pipeline possible.

```java
public record Section(String heading, List<Claim> claims, String narrative) {}
```
A chunk of the final report. `narrative` is explicitly "connective tissue
only" — filler sentences that link the claims together for readability,
but which must never sneak in an unverified fact of their own.

```java
public record Report(String summary, List<Section> sections) {}
```
The whole document.

```java
public enum Verdict { SUPPORTED, PARTIAL, UNSUPPORTED, CONTRADICTED, UNREACHABLE }
```
The Critic's grade for one claim.

```java
public record ClaimVerdict(UUID claimId, Verdict verdict, String evidencePassage) {}
```
A verdict **plus the exact sentence from the source** that justifies it.
Storing the real evidence text (not just "yep, checks out") is what turns
the Critic's output into something a human reader can double-check
themselves, instead of just trusting the AI's word for it.

```java
public record AgentEvent(UUID runId, String type, String payload, Instant timestamp) {}
```
A small progress ping meant to be streamed live to the browser in Week 4
("sub-question 3 of 7 started"). `payload` is kept as a raw JSON string
rather than a fixed shape, so new kinds of events can be added later
without a breaking change to this record.

**Verified:** `mvn -pl common -am install` — clean build. That was also the
proof the Java-version fix above actually worked.

---

## 4. Stocking the toolbox: Maven dependencies for `agent-service` and `control-plane`

No new logic yet — this section is "installing the tools," not "building
the machine." Both services were nearly empty before today.

### `agent-service/pom.xml` — additions

- **`common`** — so it can use the 10 records above.
- **`spring-boot-starter-kafka`** — Kafka is the message queue the services
  use to hand work to each other. `agent-service` will read sub-questions
  off one queue (`research.subtasks`) and write findings to another
  (`research.findings`).
- **`spring-ai-starter-model-anthropic`** — the library that lets Java code
  call Claude (Haiku for cheap steps like generating search queries;
  Sonnet for the expensive "write the final answer" step).
- **`spring-ai-starter-model-transformers`** — explained in the RAG section
  below.
- **`spring-ai-starter-vector-store-pgvector`** — also explained below.
- **`spring-boot-starter-jdbc`** + the Postgres driver — so `agent-service`
  can read/write rows directly (the Critic will persist sources, claims,
  and verdicts).
- Test-scope versions of the Kafka/web-test starters, so tests can run
  without a real Kafka broker.

I also had to add a small extra block:
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.0.3</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
This is Maven's way of saying "whenever I ask for any Spring AI library in
this module, use version 1.0.3 consistently" — a "bill of materials"
(BOM) — rather than typing a version number next to every single Spring AI
dependency individually and risking them drifting out of sync with each
other.

### `control-plane/pom.xml` — additions

- **`common`**
- **`spring-boot-starter-kafka`** — `control-plane` is the traffic cop
  (per its README description: "planner, fan-out, fan-in, budget"). It
  will *publish* sub-questions onto the queue and separately *listen* for
  findings coming back, to know when a whole research run is complete.

Nothing runs yet from either change — these are the ingredients, not the
dish. Next real step is writing actual classes: Kafka topic/consumer
config, the researcher loop, the planner, and the fan-in counter.

---

## 5. Adding RAG as a first-class, resume-describable piece

This came from a conversation, not from `docs/PLAN.md` directly — but it
turns out to fit the plan almost exactly, and it's worth understanding
properly since it's a heavily-asked-about interview topic.

### What RAG is, plainly

**RAG = Retrieval-Augmented Generation.** The problem: an LLM only knows
what was in its training data, plus whatever text you paste into the
prompt. If you want it to answer using *your own* documents — news
articles fetched from the web, in this project's case — you can't just
paste the whole thing in; it's too long, too expensive, and the relevant
sentence gets buried in noise.

RAG's fix, three steps:

1. **Chunk** — break documents into small pieces (a paragraph or so each).
2. **Embed** — turn each chunk into a list of numbers (an "embedding")
   such that chunks with *similar meaning* end up with *similar numbers*,
   even if they don't share many of the same words. ("revenue grew 12%"
   and "sales increased by twelve percent" land close together.)
3. **Retrieve** — when a question comes in, embed the question too, then
   do fast math (comparing number-lists) to find which chunks are
   closest. Hand only those top few chunks to the LLM, and have it answer
   using *only* that material.

The name says exactly what it does: Retrieve first, then Augment the
LLM's Generation with what you retrieved.

### Where it was already hiding in the PLAN, half-built

Look at the original researcher loop in `docs/PLAN.md`:
```
4. retrieval-service.extract(top 5)     → clean text (cached)
5. extract candidate passages per source (Haiku)
6. synthesize answer with source refs   (Sonnet)
```
Step 5, as originally scoped, meant "make an extra AI call to Haiku, per
source, just to find the relevant paragraph." That's not RAG — it's just
another LLM prompt, and it costs money and time for every single source on
every single sub-question.

**The RAG version:** chunk each extracted article, embed the chunks once,
store them in Postgres (via the `pgvector` extension — already sitting
unused in `docker-compose.yml` since Week 1), then do a similarity search
against the sub-question text to instantly pull back the top few relevant
passages — **no LLM call needed for that step at all.** Step 6 then asks
Sonnet to write the answer using only those retrieved passages.

Net effect: cheaper than the original plan, not more expensive, *and* it's
the textbook pattern people mean when they say "RAG" in an interview.

### The bonus: Week 3's Critic loop is the exact same pattern

```
2. chunk, embed, retrieve top-3 passages by cosine to claim text
3. grade with Haiku
```
Also RAG — retrieve the passage that best matches a claim, then use it to
ground a fact-check verdict. So the chunking/embedding/retrieval code
becomes **one shared utility**, used for two different jobs: helping the
Researcher write grounded answers, and helping the Critic verify claims.
Same mechanism, two use cases — a good, concrete story to tell in an
interview.

### The decision on which embedding tool to use

An "embedding model" is what actually turns a sentence into that
number-list. Options considered:

- **A paid API** (Voyage AI, OpenAI, etc.) — needs a new account, a new API
  key, costs money per call, depends on the network being up.
- **A small model running locally, inside the Java process** — free, no
  key, works offline, and gives the *exact same* numbers every time given
  the same input (deterministic).

Picked the local option: `spring-ai-starter-model-transformers`, a small
(~90MB) ONNX model that ships as part of Spring AI itself. No extra
account. No extra cost. And determinism matters specifically because this
project's evals (Week 4) are required to run on "fixture mode" and cost
$0 — a network-dependent paid API would break that guarantee the moment
the network hiccups or a key expires.

Storage/search side: `spring-ai-starter-vector-store-pgvector` — the
library that lets those number-lists be stored in, and searched from,
Postgres. Both dependencies are already added to `agent-service/pom.xml`
(see section 4) — no extra scope or schedule cost, since they were picked
before the RAG framing even came up; this just gives them a clearer
purpose.

### Docs updated to reflect this

- `CLAUDE.md` — added a short architecture-decision entry documenting that
  the Researcher's passage-selection step and the Critic's evidence-match
  step both go through the same shared RAG utility, and why local
  embeddings were chosen over a paid API.
- `docs/LEARNING.md` — added a dedicated "Retrieval-Augmented Generation
  (RAG)" section under the interview-prep material, covering: what RAG is,
  chunking strategy, why cosine similarity, local vs API embeddings, how
  `pgvector` does the nearest-neighbor search, and the two places this
  project actually uses it.

---

## What's next

1. Wire the actual Kafka settings (topic names, consumer group config,
   the `max-poll-interval-ms` trap already documented in `CLAUDE.md`) into
   both services' config files.
2. **Blocked on:** an `ANTHROPIC_API_KEY` in your shell environment — none
   of the actual LLM-calling code can be tested until that's set.
3. Once unblocked: the shared chunk/embed/retrieve utility, then the
   researcher loop, the planner, and the fan-in counter.
