# Kafka, explained from scratch

This doc walks through every Kafka-related class and config value in this
project, in the order a message actually flows. Read it top to bottom once,
then go open each class file while re-reading its section — that's the
order this was written for.

Everything here is real, read straight from the code as it exists today —
no invented behavior.

---

## Part 1 — What even is Kafka, for this project

Forget the general Kafka theory for a second. In this project, Kafka is
just **a row of mailboxes that don't lose mail, even if the mail carrier
crashes**.

- A **topic** is one mailbox slot's name — like `research.subtasks`. It's
  just a label.
- A **producer** is any code that drops a letter (a message) into a
  mailbox.
- A **consumer** is any code that picks letters up out of a mailbox and
  does something with them.
- The magic part: if the consumer crashes *while reading a letter*, that
  letter is **not lost**. It's still sitting in the mailbox. When the
  consumer comes back (or a replacement consumer starts), it picks up
  exactly where it left off.

That last point is the entire reason this project uses Kafka instead of,
say, one service just calling another service's REST endpoint directly.
A REST call is "hey, do this right now, and if you're down, tough luck,
the message is gone." Kafka is "hey, do this whenever you're next
available — even if you die halfway through, someone will still get it
done."

This project uses Kafka for the **slow, multi-step research work**
(planning → researching → writing → verifying) and plain HTTP for the
**quick, in-and-out lookups** (asking retrieval-service to search or fetch
a page). That split is deliberate — see `CLAUDE.md`'s rule: *"Kafka for
long-running/durable hops. HTTP for cache lookups."*

---

## Part 2 — The big picture: one question's journey

Here's the whole trip a single user question takes, start to finish. Every
arrow below is a Kafka topic (a mailbox).

```
You submit a question
        |
        v
  [control-plane: PlannerService]
   "breaks your question into 6-8 smaller sub-questions"
        |
        |  drops one letter per sub-question into...
        v
  MAILBOX: research.subtasks   (12 slots/partitions — the widest mailbox,
                                 because up to 8 letters can land here at once)
        |
        |  picked up by up to 6 workers at the same time...
        v
  [agent-service: ResearchSubtaskListener -> ResearcherService]
   "researches ONE sub-question: search the web, read pages, write an answer"
        |
        |  drops one letter (its finished answer) into...
        v
  MAILBOX: research.findings   (6 slots)
        |
        |  picked up one at a time by...
        v
  [control-plane: FindingListener -> FanInService]
   "keeps a tally in Postgres: how many of the 8 sub-questions are done?"
        |
        |  once ALL 8 are done, drops ONE letter (not eight) into...
        v
  MAILBOX: run.ready   (3 slots)
        |
        |  picked up by...
        v
  [agent-service: RunReadyListener -> WriterService]
   "turns all 8 answers into a list of individual, fact-checkable claims"
        |
        |  drops one letter into...
        v
  MAILBOX: claims.ready   (3 slots)
        |
        |  picked up by...
        v
  [agent-service: ClaimsReadyListener -> CriticService]
   "re-checks every single claim against its real source, one more time"
        |
        v
  Your report is done. Status: VERIFIED / PARTIAL / UNVERIFIED.
```

Notice this never uses Kafka to talk back to your browser. The browser
finds out what's happening by asking Postgres directly, over and over,
every second (that's the SSE — Server-Sent Events — page you saw). Kafka
is purely the "backstage" communication between services. There's actually
a 5th mailbox, `agent.events`, that was built for exactly this purpose
(pushing live progress updates) — but it's currently unused. The SSE page
ended up just asking Postgres directly instead, which turned out to be
simpler, so `agent.events` is declared and ready but nothing writes to it
yet.

---

## Part 3 — The 5 mailboxes (topics), one by one

All defined in one place: `common/src/main/java/.../KafkaTopics.java`.
It's deliberately just a list of plain text constants — no Kafka-specific
code lives in `common`, because `common` is shared by all three services
and is meant to stay as dependency-free as possible.

| Topic name | Who writes to it | Who reads from it | Slots (partitions) | Why that many slots |
|---|---|---|---|---|
| `research.subtasks` | control-plane's planner | agent-service (RESEARCHER) | 12 | Widest one — up to 8 sub-questions from one run can land here simultaneously, and you want room for several runs at once too |
| `research.findings` | agent-service (RESEARCHER) | control-plane's fan-in | 6 | One letter per sub-question's *answer* — same traffic shape as above, just the return trip |
| `run.ready` | control-plane's fan-in | agent-service (WRITER) | 3 | Only ONE letter per whole run (not per sub-question) — much less traffic |
| `claims.ready` | agent-service (WRITER) | agent-service (CRITIC) | 3 | Same — one letter per run |
| `agent.events` | *(nobody yet)* | *(nobody yet)* | 3 | Built for future live-progress pushes; not wired up |

**Where do these mailboxes actually get created?** They don't exist by
magic — something has to tell the Kafka broker "please create a mailbox
called `research.subtasks` with 12 slots." That's
`control-plane/.../config/KafkaTopicConfig.java`. It declares each topic
as a small Spring "bean" (a `NewTopic` object), and Spring Kafka creates
them automatically the moment `control-plane` starts up. This matters
because the Kafka broker used here (Redpanda, a Kafka-compatible engine)
has **no persistent storage** in this project's Docker setup — every time
you run `docker compose up` fresh, the broker starts empty, mailboxes and
all. Without `KafkaTopicConfig`, you'd have to manually recreate every
topic by hand after every restart.

---

## Part 4 — Following one message through the code, class by class

This is the part to read side-by-side with the actual files.

### Step 1 — `control-plane/planner/PlannerService.java`

This is where a run begins. `submit(question)` does three things:
1. Writes a row to the `runs` table in Postgres (status `RUNNING`).
2. Calls DeepSeek (the LLM) to break your question into 6-8 sub-questions.
3. **For each sub-question**, builds a `ResearchSubtask` (a small record —
   just `runId`, `nodeId`, `level`, `subQuestion`) and sends it:
   ```java
   kafkaTemplate.send(KafkaTopics.RESEARCH_SUBTASKS, nodeIds.get(i).toString(), subtask);
   ```

Notice the second argument — `nodeIds.get(i).toString()`. That's the
**message key**, and it matters a lot (explained in Part 5). Short
version: using a different key (`nodeId`) for every sub-question is what
spreads all 8 of them across different partitions, so they can actually
run *in parallel* instead of all queuing up behind each other.

### Step 2 — `agent-service/researcher/ResearchSubtaskListener.java`

This class exists purely to sit and wait:
```java
@KafkaListener(topics = KafkaTopics.RESEARCH_SUBTASKS, groupId = "agent-service-researcher")
public void onSubtask(ResearchSubtask subtask) {
    ResearchFinding finding = researcherService.research(subtask);
    kafkaTemplate.send(KafkaTopics.RESEARCH_FINDINGS, subtask.runId().toString(), finding);
}
```
The `@KafkaListener` annotation is doing all the heavy lifting here —
Spring wires this method up to run automatically, every single time a new
letter shows up in the `research.subtasks` mailbox. This one class is why
you never see any "poll the queue" loop anywhere in the code — Spring
Kafka handles that loop for you, behind the scenes.

The actual research work (search, read pages, write an answer) is
`ResearcherService.research(...)` — a plain Java class with zero Kafka
code in it. That's deliberate: the listener's only job is "receive a
letter, hand it to the real worker, mail back the result." Keeping Kafka
code and business logic in separate classes means you could test
`ResearcherService` with zero Kafka running at all (and the tests do
exactly that).

### Step 3 — `control-plane/fanin/FindingListener.java` + `FanInService.java`

`FindingListener` is the same shape as Step 2 — a thin `@KafkaListener`
that just hands the message off:
```java
@KafkaListener(topics = KafkaTopics.RESEARCH_FINDINGS)
public void onFinding(ResearchFinding finding) {
    fanInService.recordFinding(finding);
}
```

`FanInService.recordFinding(...)` is where the actually clever bit lives —
**how do we know when all 8 sub-questions are done, when up to 6 of them
might finish at the exact same instant?**

The answer is one line of SQL:
```sql
UPDATE dag_levels SET completed = completed + 1
WHERE run_id = ? AND level = ?
RETURNING completed, expected
```
Postgres guarantees that if two of these `UPDATE`s hit the same row at the
same instant, they get processed one after the other, never at the same
time (this is called row-level locking). So even if 6 findings land in
the same millisecond, each one gets back a genuinely different, correctly
incremented number. **Exactly one** of those 6 will see
`completed == expected` (e.g. `8 == 8`) — and only that one goes on to
publish to `run.ready`. Everyone else just quietly does nothing more.

Why not just keep this count in a Java variable in memory? Because if
`control-plane` crashes and restarts mid-run, an in-memory counter is
gone — the run would be stuck forever. A number sitting in a Postgres
table survives a restart. This is explicitly called out as a project rule
in `CLAUDE.md`: *"Fan-in state must NOT live in a ConcurrentHashMap or
AtomicInteger... it must be in Postgres."*

### Step 4 — `agent-service/writer/RunReadyListener.java` + `WriterService.java`

Same shape again: a tiny listener hands off to a real worker class.
`WriterService.write(runId)` reads all 8 findings back out of Postgres,
asks DeepSeek to turn them into individual, checkable claims (not one big
paragraph of prose), saves those claims to Postgres, and sends one
`ClaimsReady` letter to `claims.ready`.

### Step 5 — `agent-service/critic/ClaimsReadyListener.java` + `CriticService.java`

Last stop. `CriticService.verify(runId)` re-fetches every single source
URL that got cited, and asks DeepSeek to grade each claim against the
freshly-fetched text: SUPPORTED, PARTIAL, UNSUPPORTED, or CONTRADICTED.
Once every claim has a verdict, it flips the run's status in Postgres to
`VERIFIED` (or `UNVERIFIED` if too many claims failed). Nothing gets
published to Kafka after this — the trip is over.

---

## Part 5 — The config knobs, explained one at a time

These live in `application.yml` under `spring.kafka:` in both
`agent-service` and `control-plane`. Every single one of these exists
because of a **real bug that actually happened** during development, not
because it's textbook best practice — that history is worth knowing,
because it's the "why," not just the "what."

### `bootstrap-servers: localhost:9092`
Just the address of the Kafka broker (Redpanda) to connect to. Nothing
fancy — this is the phone number to call to reach the mailroom.

### `group-id` / `groupId` (a different one per listener!)
This is the single most important concept to actually understand.

A **consumer group** is Kafka's way of saying "these consumers are a team
sharing the work of one mailbox." If a topic has 12 partitions and 6
consumers join the same group, Kafka automatically splits those 12
partitions across the 6 consumers (2 each) — that's what makes parallel
processing happen. If a 7th consumer joins the same group, Kafka
re-shuffles the partitions again to include it.

Every single listener in this project (`ResearchSubtaskListener`,
`RunReadyListener`, `ClaimsReadyListener`) has its **own, explicit**
`groupId` — `"agent-service-researcher"`, `"agent-service-writer"`,
`"agent-service-critic"`. This was a real bug fix: the comment in
`ResearchSubtaskListener` explains that if you let two listeners that
subscribe to *different topics* share one global default group id, Kafka
gets confused about how to split up partitions fairly (because the group
now has members interested in different topics), and it gets stuck in an
endless loop of trying and failing to agree on an assignment. Giving each
listener its own group id sidesteps the whole problem.

### `max-poll-records: 1`
"When you go check the mailbox, only bring back ONE letter, not a stack of
them." Why? Because researching one sub-question can take up to 90
seconds. If a consumer grabbed, say, 10 subtasks at once, it would have to
process them one by one *before even acknowledging the first one* — so 9
of those subtasks would sit doing nothing for up to 15 minutes, even
though other free consumers could've been working on them. Taking exactly
one at a time keeps work spread out evenly.

### `max.poll.interval.ms: 600000` (10 minutes)
This is Kafka's patience limit: "how long can a consumer go without
saying 'I'm still alive and working' before I assume it's dead and give
its work to someone else?" The **default is 5 minutes**. A researcher
task can legitimately take close to 90 seconds just for one LLM call, and
with retries/slow networks that can add up. This bit the project for
real: with the default 5-minute limit, a slow subtask would get silently
evicted from its consumer group mid-work, Kafka would hand the *same*
message to another consumer (because the first one never finished), and
now the same sub-question gets researched twice — a duplicate, with no
error message anywhere. Bumping this to 10 minutes gives plenty of
headroom. This exact trap is written down in `CLAUDE.md`'s "Traps that
have already cost time" section, because it was that painful to debug.

### `auto-offset-reset: earliest`
If a consumer group has *never* read a topic before (brand new group,
first time ever connecting), should it start reading from the very
beginning of the mailbox's history, or only wait for brand-new letters
from this point on? `earliest` means "start from the beginning" — useful
in development, where you don't want to lose a message just because your
consumer happened to start up a few seconds late.

### `key-serializer` / `value-serializer` (and the `de-` versions for reading)
A Kafka message is really just raw bytes on a wire — it doesn't know what
a `ResearchSubtask` Java object is. A **serializer** is the translator
that turns a Java object into bytes on the way out; a **deserializer**
turns those bytes back into a Java object on the way in. This project
uses `StringSerializer` for keys (the keys are just plain UUID strings)
and `JsonSerializer`/`JsonDeserializer` for the actual message bodies —
meaning every message on the wire is just a JSON blob, the same JSON
you'd see if you printed the object.

### `spring.json.trusted.packages: com.comeback.researchplatform.common`
A safety rule for the deserializer: "only ever try to turn incoming JSON
into a class that lives in the `common` package — refuse everything
else." Without this, a malicious or corrupted message could theoretically
trick the deserializer into instantiating some arbitrary class on your
classpath. Since every real message type (`ResearchSubtask`,
`ResearchFinding`, `RunReady`, `ClaimsReady`) genuinely does live in
`common`, this is a free safety net with zero downside.

### `spring.json.value.default.type` (control-plane only) vs. header-based typing (agent-service)
This answers: "given a blob of JSON just arrived, which Java class should
it become?" `control-plane`'s `FindingListener` only ever listens to one
topic carrying one message type (`ResearchFinding`), so it just hardcodes
that as the default type. `agent-service`, though, listens to *two
different topics carrying two different message types*
(`ResearchSubtask` on one, `RunReady` on another) — a single fixed default
type doesn't work there. Instead it relies on a small extra header
(`__TypeId__`) that `JsonSerializer` automatically stamps onto every
message when it's sent, recording which exact class it came from. The
consumer reads that header to know what to deserialize into. This is
Spring Kafka's built-in mechanism — nothing custom had to be written for
it, just configured correctly.

### `listener.concurrency: 6`
How many parallel worker threads should pull from a topic's partitions at
once, within one Java process. This is the literal number that makes "6
researchers run in parallel" a true sentence rather than marketing copy —
it's set to 6 specifically because that's the number PLAN's Week 2 goal
asks for, and `research.subtasks` has 12 partitions (2 per worker) to give
that number room to actually matter.

---

## Part 6 — How to actually watch this happen

If you want to see all of this live instead of just reading about it:

1. Make sure `docker compose up -d` is running (this starts Redpanda).
2. Start `control-plane` and `agent-service`.
3. Submit a run (`POST /api/v1/runs` or the Verify tab in the console).
4. Watch `agent-service`'s terminal output — you'll see log lines like
   `Researching sub-question '...' (run ...)` appear, several at once, as
   different partitions get picked up by different worker threads. That's
   `ResearchSubtaskListener.onSubtask` firing in real time.
5. `docker exec -it research-redpanda rpk topic consume research.subtasks`
   lets you watch the raw JSON messages land in a topic directly, bypassing
   the Java app entirely — good for confirming exactly what's on the wire.

---

## One honest gap, so you don't go looking for something that isn't there

`agent.events` and the `AgentEvent` record exist in the code and the topic
is created on startup — but nothing publishes to it, and nothing consumes
it. It was planned as the mechanism for pushing live progress updates to
the browser, but the SSE page ended up just polling Postgres directly
every second instead, which was simpler to get working. If you go looking
for the code that uses this topic, you won't find it — that's not a bug in
your reading, it's genuinely unused right now.
