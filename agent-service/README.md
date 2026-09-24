# agent-service

**Port 8082.** The AI workers. One Spring Boot app with three roles, each
switched on by a **Spring profile** (a config value that decides which
classes get loaded). Locally all three run together:
`spring.profiles.active: RESEARCHER,WRITER,CRITIC`.

| Role | Listens to (Kafka) | Does | Sends |
|---|---|---|---|
| **RESEARCHER** | `research.subtasks` | answers one sub-question from real web pages | `research.findings` |
| **WRITER** | `run.ready` | turns every answer into short claims, one source each | `claims.ready` |
| **CRITIC** | `claims.ready` | fact-checks every claim, fixes or removes failed ones, writes the conclusion | — (sets the run's final status) |

The AI model is **DeepSeek**, reached through Spring AI's OpenAI-compatible
client (DeepSeek's API is shaped like OpenAI's).

## The code, by package

| Package | Main classes | Job |
|---|---|---|
| `researcher` | `ResearcherService` | queries → search → read pages → pick passages → answer → confidence score |
| `writer` | `WriterService` | findings → claims (FACT, FIGURE, QUOTE or INFERENCE), each tied to one source |
| `critic` | `CriticService`, `ClaimCorrector`, `ConclusionWriter` | grade claims → retry the failed ones → write the conclusion from claims that passed |
| `rag` | `PassageStore` | split pages into passages, store them as embeddings, find the closest ones |
| `retrieval` | `HttpRetrievalClient`, `FixtureRetrievalClient` | talk to retrieval-service (real or replayed) |
| `guardrail` | `RunUsageGuard` | per-run limits on searches and AI calls, counted in Postgres |
| `fixtures`, `chat` | `FixtureIO`, `FixtureChatModel` | record and replay search and AI replies (see below) |
| `activity` | `RunActivityLog` | the plain-English lines shown in the browser's activity feed |
| `eval` | `ClaimCorruptor`, `FabricationEval` | the fabrication eval: break claims on purpose, check the critic catches them |
| `util` | `UrlHost` | "https://www.rbi.org.in/x" → "rbi.org.in" for display |

## Fixture mode: replaying instead of calling

- **Record:** run with `fixtures.record-mode: true`. Every real search and AI
  reply is saved to JSON files under `src/main/resources/fixtures/`.
- **Replay:** run with the `fixture` profile. Saved replies are used instead of
  the network: free, fast, and the same every time. A request that was never
  recorded fails loudly rather than pretending nothing was found.

## Key settings (`application.yml`)

| Block | Controls |
|---|---|
| `researcher` | time and token budget, queries per sub-question, pages to read, passages to use |
| `writer` | max claims per answer |
| `critic` | batch size, passages per claim, failure threshold for `UNVERIFIED`, max claims to retry, parallel AI calls |
| `guardrails.run` | per-run limits: searches, AI calls, critic rounds |
| `spring.kafka` | why each Kafka setting is what it is: see [docs/KAFKA.md](../docs/KAFKA.md) |

## Why one app with profiles, not three services

The three roles need the same libraries, the same database and the same Kafka
setup. What differs is mostly which prompt runs. Three separate apps would
triple the images, the config and the things that can break, for no gain at
this size. If one role ever needs very different scaling, it can be split out
by starting a copy with just that profile.

## Rules it follows

- Never calls a search engine directly; always goes through retrieval-service.
- The writer emits **claims, not paragraphs**. A paragraph with footnotes
  can't be checked sentence by sentence.
- The critic stores the **evidence sentence**, not just the grade, so a person
  can check the grade.
- A rewritten claim is graded again by the same independent grader. The model
  that fixed a claim never approves its own fix.

## Run and test

```bash
mvn -pl agent-service -am test          # unit tests, using a fake AI model, no network
mvn -pl agent-service spring-boot:run   # needs Postgres, Kafka, retrieval-service, DEEPSEEK_API_KEY
```

The tests use `ScriptedChatModel`, a fake AI model that answers based on what
the prompt contains, so each service runs its real code end to end without
calling DeepSeek.
