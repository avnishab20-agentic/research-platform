# The Story of This Codebase — 00: The World

*This is the scene-setter. No flow narration yet — first you need to know what country
you're in, what weather to expect, and who lives here.*


**On this page:** [In plain English](#in-plain-english-30-seconds) · [The problem](#the-problem-this-project-solves) · [The World](#the-world) · [The Cast](#the-cast)

---

## In plain English (30 seconds)

This project is a machine that answers a research question and then proves its answer.
Right now only the first half exists: the part that goes and finds web pages.

This page is the map before the journey. First it explains the problem the project is
trying to solve. Then it walks through every tool and library in the box, one at a time,
in ordinary words. Then it introduces every class in the code as if it were a character
in a play, with a job title you can remember.

After reading this you will know what each moving part is for, and which parts are
finished versus which are still just plans on paper. You will not yet know the exact
order things happen — that is what the numbered flow files after this one are for.

---

## The problem this project solves

You type a question like *"What has the RBI done about inflation this year?"* and the
internet hands you a million pages. Some are excellent. Some are nonsense. Search
engines are good at *ranking* pages, but ranking is a popularity guess. A search engine
never actually **checks** whether a sentence is true.

So this project builds a small factory. The factory takes your question and breaks it
into smaller sub-questions. It hands each sub-question to a separate worker running at
the same time as the others. Each worker goes and reads web pages. Their findings get
written up into one report.

Then comes the part that is the whole point. The report is taken apart claim by claim.
For every single factual sentence, the machine goes back to the web page that sentence
came from and looks for the exact passage that supports it. That passage is saved
alongside the claim. So the finished report does not just say "this is true" — it hands
you the evidence and says "here, check me."

Today (2026-08-23), only the "go find information" half exists as running code. That is
the librarian, a service called `retrieval-service`, plus a small text-cleaning helper
written in Python. The worker agents (Weeks 2–3 of the plan) and the results web page
(Week 4) are still blueprints — designed, written down, not built. This guide narrates
what is real and points at the blueprints without pretending they are built.

---

## The World

Below is every piece of infrastructure and every library that appears in this repo.
For each one: what it is in plain words, what it is *like*, what job it does **here
specifically**, and what would break if you deleted it.

First, the shape of the whole thing. Solid arrows are code that runs today. Dotted
arrows are wired-up-but-nobody-calls-it, or blueprint.

```text
A person with a question
   │  future: blueprint chain, not wired yet
   ▼
control-plane, port 8083 (blueprint: only an empty startup class)
   │  future: not wired yet
   ├──► future: Postgres (the filing cabinet, tables exist, all empty)
   │
   ▼
agent-service, port 8082 (blueprint: only an empty startup class)
   │  future: not wired yet
   ├──► future: Redpanda (the post office, installed, zero Java code uses it)
   │
   ▼
retrieval-service, port 8081 (BUILT AND WORKING)
   │  working today (solid)
   ├──► Redis: the notepad on the desk
   ├──► SearXNG: the newsstand outside
   └──► future: Python extractor (strips ads off a page, built but
        nothing calls it yet)

You, testing by hand with curl
   │  working today (solid)
   ▼
retrieval-service, port 8081 (BUILT AND WORKING)
```

A quick word on two words used constantly below.

A **service** here means one program that runs on its own and answers requests over the
network. This repo has three of them, and only one has real behaviour so far.

A **container** means a program packed in a sealed box with everything it needs. More on
that under Docker.

### Java 21 + Maven multi-module (`pom.xml`)

**What it is.** Maven is a build tool. A build tool is the program that turns your
folder of `.java` text files into something runnable, downloads the outside libraries
you asked for, and runs your tests. You tell it what you want in a file called
`pom.xml`. XML — eXtensible Markup Language — is just a text format made of nested
tags, like HTML.

**Analogy.** Maven is the project's general contractor. You hand it a set of recipe
cards and it does the shopping, the assembly, and the inspection.

**The job here.** This repo has five recipe cards: one at the top and one inside each
module. A **module** is one sub-project inside a bigger project. The top card is called
an *aggregator*, meaning it holds no code of its own. You can see that in
`pom.xml:10`, where `<packaging>pom</packaging>` says "I am a list, not a program."
Right below it, `pom.xml:11-16` lists the four children: `common`, `retrieval-service`,
`agent-service`, `control-plane`.

Because they are listed together, one command builds a module and everything it needs:

```
mvn -pl retrieval-service -am test
```

`-pl` means "in this project list" and `-am` means "also make the modules this one
depends on."

The top card also decides version numbers for everybody. At `pom.xml:22-32` it imports
a big catalogue of library versions published by Spring Boot, version 4.1.0 — every
piece already picked to fit with every other piece. That is why the child cards can ask
for a library *without* naming a version — for
example `retrieval-service/pom.xml:16-19` just says "give me the actuator starter" and
the version comes from the parent. Separately, `pom.xml:35-43` pins the version of the
Spring Boot packaging plugin, which each runnable service then names without a version
(for example `retrieval-service/pom.xml:52-55`).

**If it vanished.** You would lose the one-command build, test, and dependency
download. Nothing at runtime needs Maven — once the program is built, Maven's job is
over.

> **Suspicious:** the top `pom.xml:18-20` declares `<java.version>21</java.version>`,
> but nothing connects that value to the Java compiler. Only
> `retrieval-service/pom.xml:45-48` does the connecting, with
> `<maven.compiler.release>${java.version}</maven.compiler.release>`. So today only
> `retrieval-service` is provably compiled as Java 21. The `agent-service` and
> `control-plane` cards (`agent-service/pom.xml:28-30`,
> `control-plane/pom.xml:27-29`) set the property but never wire it in. They compile
> fine right now because their only class uses nothing modern. The first `record`
> somebody writes in them may fail with a baffling "records are not supported in
> -source 8" error. That exact trap already bit `retrieval-service` once — see
> `CLAUDE.md`, Session 4.

### Spring Boot 4.1.0 (+ embedded Tomcat, via `spring-boot-starter-webmvc`)

**What it is.** Spring Boot is a framework: a big pile of pre-written code that handles
the boring, repeated parts of building a server, so you only write the parts that are
specific to your app. "Opinionated" means it picks sensible defaults for you instead of
asking a hundred questions.

**Analogy.** Buying a kit house instead of milling your own lumber. The walls, wiring,
and plumbing come pre-fitted; you decide what goes in the rooms.

**The job here.** Each of the three services has exactly one class marked
`@SpringBootApplication` with a normal Java `main()` method. For example, the
annotation sits at `RetrievalServiceApplication.java:7` and the `main()` at
`RetrievalServiceApplication.java:11-13`. An **annotation** is the `@Something` label
you stick on a class or method; by itself it does nothing, it is a note that some other
code reads later.

When you run that `main()`, Spring Boot does three big things.

First, **component scan**. It walks through your packages looking for classes labelled
`@Component`, `@Service`, `@RestController`, or `@Configuration`. For each one it finds,
it creates one instance and keeps it. Spring builds the object for you and holds onto
it. The technical name for such an object is a **bean**. `retrieval-service` alone has
several: `RetrievalController.java:10`, `SearchService.java:23`, `QuotaService.java:12`,
`SourceTierResolver.java:8`, `ExtractorClient.java:11`, plus two config classes.

Second, **auto-configuration**. Spring Boot looks at which libraries are sitting on the
classpath and quietly builds beans for them. You never wrote a line to connect to
Redis, yet a ready-made Redis helper object exists — that is auto-configuration. (The
classpath is just the list of jars the program can see when it runs.)

Third, it starts an **embedded Tomcat**. Tomcat is a web server: a program that listens
on a network port, accepts HTTP requests, and hands them to your code. HTTP —
HyperText Transfer Protocol — is the request/reply language browsers and servers speak.
"Embedded" means Tomcat lives *inside* your program rather than your program being
installed into Tomcat. So there is no "deploy to a server" step; the built program *is*
the server. In this repo Tomcat arrives as a hidden gift inside
`spring-boot-starter-webmvc` (`retrieval-service/pom.xml:24-27`) — running
`mvn dependency:tree` shows `tomcat-embed-core:11.0.22` pulled in underneath it. The
port it listens on comes from configuration:
`retrieval-service/src/main/resources/application.properties:2` sets `server.port=8081`.
The other two services set 8082 and 8083 the same way
(`agent-service/src/main/resources/application.properties:2`,
`control-plane/src/main/resources/application.properties:6`).

**If it vanished.** Remove Spring Boot and none of the three services exist at all.
Remove only Tomcat and the beans would still be built, but nothing could receive a
request from outside.

### Spring MVC annotations (`web/RetrievalController.java`)

**What it is.** Spring MVC is the part of Spring that maps incoming web addresses to
your Java methods. MVC stands for Model-View-Controller, an old naming convention;
here just read it as "the web layer."

**Analogy.** A switchboard. A call comes in for `/api/v1/search`; the switchboard knows
which method answers that line.

**The job here.** Four labels do all the work in one file.

`@RestController` (`RetrievalController.java:10`) does two things at once: it tells the
component scan "register me as a bean," and it tells Spring MVC "whatever my methods
return should be sent back as data, not as the name of a web page template." REST is
just a style of designing web endpoints around addresses and verbs.

`@RequestMapping("/api/v1")` (`RetrievalController.java:11`) puts `/api/v1` in front of
every address in the class, so you do not repeat it.

`@PostMapping("/search")` (`RetrievalController.java:21`) and
`@GetMapping("/quota")` (`RetrievalController.java:31`) attach one address plus one
verb to one method. GET means "just give me something." POST means "here is a body of
data, do something with it." There is also `@PostMapping("/extract")`
(`RetrievalController.java:26`), which today returns an empty list
(`RetrievalController.java:28`).

`@RequestBody` (`RetrievalController.java:22`) says: take the raw bytes the caller
posted, and turn them into this Java object before calling me. The turning is done by
the next character on the list.

**If it vanished.** No address would map to any method. The server would start and then
answer 404 Not Found to everything.

### Jackson (`tools.jackson.*`)

**What it is.** Jackson is a translator between JSON and Java objects. JSON —
JavaScript Object Notation — is the plain-text format almost all web APIs use to pass
data around; it looks like `{"maxResults": 10}`. An API — Application Programming
Interface — is just the set of addresses one program offers to other programs.

**Analogy.** A bilingual clerk sitting at the door. Everything coming in gets
translated from JSON into Java; everything going out gets translated back.

**The job here.** Jackson works in two different modes in this repo.

The invisible mode: it powers `@RequestBody` on the way in and the returned record on
the way out. You never call it; Spring MVC does.

The visible mode: `SearchService` calls it by hand to store things in the cache. At
`SearchService.java:63` it turns a list of results into one JSON string before saving it,
and at `SearchService.java:58` it turns that string back into a list of results after
reading it. The object doing this is called an `ObjectMapper`, handed to the service by
Spring at `SearchService.java:33`.

One detail worth flagging: this project runs **Jackson 3**, whose package names begin
with `tools.jackson` instead of the older, far more googleable
`com.fasterxml.jackson`. You can see the new-style imports at `SearchService.java:12-13`,
and `mvn dependency:tree` confirms `tools.jackson.core:jackson-databind:3.1.4`. If you
paste in a Jackson snippet from the internet, its imports probably will not compile
here.

**If it vanished.** Every endpoint would stop being able to read or write JSON, and the
search cache could no longer be stored as text.

### Records (Java feature, not a library)

**What it is.** A `record` is a short way to declare a class whose only job is to carry
data. You list the fields once inside parentheses, and Java writes the constructor, the
getter methods, `equals`, `hashCode`, and `toString` for you. The fields cannot be
changed after creation — the word for that is **immutable**.

**Analogy.** A printed form. Once it is filled in and handed over, nobody can quietly
edit a box on it.

**The job here.** Every data shape in this repo is a record. Requests and responses:
`SearchRequest.java:6`, `SearchResponse.java:5`, `SearchResult.java:3`,
`ExtractRequest.java:5`, `ExtractResponse.java:6`, `Document.java:3`,
`QuotaResponse.java:3`. Shapes for talking to outside services:
`SearxngSearchResponse.java:5`, `SearxngResult.java:3`, `ExtractorRequest.java:3`,
`ExtractorResult.java:3`. Even settings are records: `SourceTierProperties.java:7` and
`QuotaProperties.java:7`.

You will also see the letters **DTO** — Data Transfer Object — used for these. It means
exactly what it sounds like: an object whose only purpose is to move data across a
boundary. The seven under `dto/` even live in a package called that.

The house rule in `CLAUDE.md` is "records, not Lombok." Lombok is a popular library that
generates the same boilerplate using annotations; this repo deliberately does not use
it, because plain Java can now do the job.

**If they vanished.** Nothing would break conceptually, but every one of those files
would balloon from three lines to forty, and you would have to keep the hand-written
`equals` in step with the fields forever.

### `RestClient` (Spring's outbound HTTP client)

**What it is.** `RestClient` is Spring's object for *making* web requests to somebody
else. Note the direction. A controller receives calls; a `RestClient` places them.

**Analogy.** The controller is the receptionist answering the desk phone. The
`RestClient` is the office phone you pick up to call another building.

**The job here.** Two of them exist, and they are built at startup by two small
`@Configuration` classes. A `@Configuration` class is one whose only job is to hand
Spring some hand-built beans; each `@Bean` method's return value gets stored and shared.

`SearxngClientConfig.java:12-17` builds the one pointed at the search engine.
`ExtractClientConfig.java:12-17` builds the one pointed at the Python helper. Both read
their target address from configuration rather than hard-coding it, using
`@Value("${...}")` (`SearxngClientConfig.java:13`, `ExtractClientConfig.java:13`). Those
placeholders are filled from
`retrieval-service/src/main/resources/application.yml:13-14` and
`application.yml:19-20`.

Now the interesting wrinkle. Both beans have the same Java type, `RestClient`. When
Spring needs to hand a `RestClient` to somebody, "give me a `RestClient`" is suddenly
ambiguous — there are two, and Spring refuses to guess. So every place that wants one
must name it, using `@Qualifier("theBeanName")`. You can see that at
`SearchService.java:33` asking for `searxngRestClient`, and at `ExtractorClient.java:16`
asking for `extractorRestClient`. The bean's name is simply the name of the method that
built it.

**If one vanished.** The failure happens at startup, not at first use. Spring builds all
the beans up front, so a missing `RestClient` means the whole service refuses to start,
loudly, with a message naming the class that wanted it.

### Redis 7 (via `spring-boot-starter-data-redis`, client = Lettuce)

**What it is.** Redis is a place to store small pieces of data by name, extremely fast,
because it keeps everything in memory rather than on disk. Think of a giant
`Map<String, String>` living in a separate program.

**Analogy.** The notepad on the librarian's desk. Anything on it is a copy of something
she could look up again the long way; losing the notepad is annoying, never fatal.

**The job here.** Redis has exactly two jobs in this repo.

Job one is the search cache. When a search result comes back from the web, it is written
to Redis under a name starting with `search:v1:searxng:` (`SearchService.java:80`) and
kept for 24 hours (`SearchService.java:63`). The next identical search reads it straight
off the notepad (`SearchService.java:54`) and never touches the internet. That expiry
time has a name: **TTL**, Time To Live, meaning "delete this automatically after N."

Job two is the daily credit counter. Every time a real search is paid for,
`QuotaService.recordSpend()` bumps a number stored under `quota:v1:<today's date>`
(`QuotaService.java:21-25`, key built at `QuotaService.java:33`).

The Java side never talks to Redis in Redis's own language directly. Spring
auto-configures a helper bean called `StringRedisTemplate`, whose methods are simple
shorthands for Redis commands — `get`, `set`, `increment`, `expire`. It is handed to
`SearchService`
(`SearchService.java:28`, `:33`) and to `QuotaService` (`QuotaService.java:14`, `:17`).
Underneath, the library that actually opens the network connection is **Lettuce**,
pulled in by `spring-boot-starter-data-redis` (`retrieval-service/pom.xml:20-23`;
`mvn dependency:tree` shows `io.lettuce:lettuce-core:7.5.2.RELEASE`). Lettuce connects
**lazily** — meaning it does not dial until the first command is actually sent.

The container is started with two extra flags at `docker-compose.yml:22`:
`--maxmemory 256mb --maxmemory-policy allkeys-lru`. That means "you may use at most 256
megabytes, and when you hit that ceiling, throw away whichever keys were used longest
ago to make room." **LRU** stands for Least Recently Used. This matters because Redis's
default behaviour is to *refuse new writes* when full, which is wrong for a cache —
everything in a cache is disposable and can be fetched again.

Also note what is *not* configured: the Redis service at `docker-compose.yml:19-29` has
no `volumes:` entry, unlike Postgres at `docker-compose.yml:11-12`. A volume is a folder
kept outside the container so data survives. Without one, Redis contents survive
restarts of the *Java* app, but are wiped if the Redis container itself is recreated.
For a cache and a daily counter, that is acceptable.

**If it vanished.** Not "searches get slower" — searches **fail**. Because Lettuce
connects lazily, startup is fine, and then the first request that touches Redis throws.
`POST /api/v1/search` dies at `SearchService.java:54` on the very first cache read, and
`GET /api/v1/quota` dies at `QuotaService.java:28`. Both surface as a 500 Internal
Server Error.

### SearXNG (container, configured by `searxng/settings.yml`)

**What it is.** SearXNG is an open-source *metasearch* engine. Metasearch means it does
not have its own index of the web; it forwards your query to Google, Bing, and others,
then merges their answers into one list.

**Analogy.** The newsstand outside the library. It does not write the papers, it just
has all of them in one place.

**The job here.** It is the only way this project reaches the open web for search
results, and it is not our code — we run the published image
(`docker-compose.yml:53-54`) and change exactly one setting. That one setting is at
`searxng/settings.yml:9-12`, which adds `json` to the allowed output formats. Without
that, SearXNG replies with a web page meant for human eyes, and our JSON parser chokes
on it — a documented trap that has already cost time on this project. The container
publishes port 8080 (`docker-compose.yml:56-57`), which is exactly the address the Java
side is configured to call (`application.yml:13-14`).

**If it vanished.** Every search that is not already sitting in the Redis cache fails.
Cached searches keep working until their 24 hours expire.

### The extractor sidecar (`extractor/main.py`, FastAPI + uvicorn + pydantic + trafilatura)

**What it is.** A tiny web service written in Python instead of Java — 44 lines in one
file. The word **sidecar** means a small helper program that runs next to a main program
and does one narrow job for it.

**Analogy.** The specialist you call in to strip the ads, menus, and cookie banners off
a printed page so only the article is left.

**The job here.** You send it the raw HTML of a page you already downloaded. It sends
back the clean article text, the title, the publication date, and a one-word verdict.
Four Python libraries make that happen.

**FastAPI** (imported at `main.py:1`, app created at `main.py:5`) is the Python
equivalent of Spring MVC in miniature: you decorate a function and it becomes a web
address. `@app.get("/health")` at `main.py:23` and `@app.post("/extract")` at
`main.py:28` are the two doors.

**pydantic** (`main.py:2`) is the shape-checker. The two classes at `main.py:10-20`
declare exactly which fields the incoming and outgoing JSON must have, and pydantic
enforces it. Send `/extract` a body with no `html` field and pydantic rejects it before
your function ever runs. It is doing the job that records plus Jackson do on the Java
side.

**uvicorn** is the actual server process — Python's answer to Tomcat. It is not
imported in the code; it is the command that launches everything, at
`extractor/Dockerfile:7`. The `--host 0.0.0.0` part matters: it means "accept
connections from outside this container's sealed box," without which the published
port would reach nothing.

**trafilatura** (`main.py:3`) is the real specialist. Its entire talent is reading
messy real-world HTML and returning just the article. Called at `main.py:30` for the
text and `main.py:31` for the metadata.

The verdict logic is one line, `main.py:36`: if the extracted text has at least 200
non-blank characters (the threshold is set at `main.py:7`), the status is `OK`;
otherwise it is `PAYWALLED`. That is a guess, and a deliberate one — a paywalled article
typically yields a teaser and nothing more. Notice what this service never does: it
never fetches a URL itself. It only ever receives HTML somebody else downloaded. So it
physically cannot tell you a site was unreachable or blocked — those verdicts belong to
the Java side, at fetch time.

**If it vanished.** Nothing running today would notice, because nothing calls it yet.
But the future real `/api/v1/extract` would lose its brain — Java has no equivalent of
trafilatura in this repo.

### Docker + docker-compose (`docker-compose.yml`)

**What it is.** Docker runs a program inside a **container**: a sealed mini-computer
with its own filesystem, its own installed packages, and its own view of the network.
The program inside cannot tell it is not on a real machine. Because the box includes
everything the program needs, "but it works on my machine" stops being a thing.

An **image** is the frozen template; a **container** is one running copy of it.

**Analogy.** An image is a recipe plus all ingredients pre-measured in a sealed kit. A
container is one meal cooked from that kit.

**docker-compose** is the conductor for several containers at once. You describe them in
one YAML file — YAML, YAML Ain't Markup Language, is a text format where indentation
means nesting — and then one command, `docker compose up -d`, starts them all. The `-d`
means "detached," i.e. run in the background.

**The job here.** `docker-compose.yml` describes five services: `postgres`
(`:2-17`), `redis` (`:19-29`), `redpanda` (`:31-51`), `searxng` (`:53-61`), and
`extractor` (`:64-73`).

Three details are worth understanding.

**Port mapping.** A line like `"8000:8000"` (`docker-compose.yml:68`) reads
*host-port : container-port*. It punches a hole from your laptop into the sealed box.
Without it, the program inside is running perfectly and is completely unreachable.

**Healthchecks.** A `healthcheck:` block (for example `docker-compose.yml:69-73`) is a
small command Docker runs inside the container every few seconds. If it succeeds, the
container is reported "healthy." That is what lets `docker compose ps` tell you the
difference between "started" and "actually working." The extractor's healthcheck
(`docker-compose.yml:70`) is written in raw Python rather than `curl`, because the slim
Python base image does not ship `curl`.

**Build versus pull.** Four of the five services name a ready-made public image, for
example `image: redis:7` (`docker-compose.yml:20`). Only the extractor says
`build: ./extractor` (`docker-compose.yml:65`), meaning "construct this image from my
own recipe" — the recipe being `extractor/Dockerfile`.

One thing this file does *not* contain: the three Java services. They are run from the
IDE or from Maven, on the host machine, not in containers. That is why they talk to
`localhost` (`application.yml:14`, `application.yml:20`).

**If it vanished.** You would have to install and hand-start Postgres, Redis, Redpanda,
SearXNG, and Python-plus-trafilatura on your own machine, with matching ports and
matching versions, and keep them in step forever.

### Postgres 16 + pgvector image (container) + Flyway + Hibernate/JPA

**What it is.** Postgres is a relational database. "Relational" means data lives in
tables with named columns, and you query it with SQL — Structured Query Language.
Unlike Redis, it writes to disk, so it is the place for things you must not lose.

**Analogy.** The filing cabinet. Slower than the notepad, but it is still there
tomorrow.

**The job here.** The image chosen is `pgvector/pgvector:pg16`
(`docker-compose.yml:3`), which is ordinary Postgres 16 with an add-on called pgvector
bolted in. pgvector lets you store the numeric fingerprints that AI models produce for
text and search by similarity. No table uses it yet; it is chosen now so nobody has to
migrate later. The data lives in a named volume (`docker-compose.yml:11-12` and
`:75-76`), so it survives the container being recreated.

Only `control-plane` connects to it, configured at
`control-plane/src/main/resources/application.properties:2-4`.

**Flyway** is a database migration tool. A migration is a numbered SQL file describing a
change to the database's shape (its tables and columns). Flyway runs each one exactly
once, in number order, and records what it ran in a bookkeeping table called
`flyway_schema_history`. That way every developer's database ends up identical, and the
history is in Git.

**Analogy.** A ship's logbook that is also the instructions. Anyone can replay it from
zero and arrive at the same ship.

Here there is one migration, `V1__init_schema.sql`. It creates three tables: `runs`
(`V1__init_schema.sql:1-8`), which will hold one row per research question;
`dag_nodes` (`:10-20`), one row per sub-question; and `dag_levels` (`:23-30`), the
counter table that will later let the system know when all workers in a batch have
finished. DAG stands for Directed Acyclic Graph, a way of describing tasks that depend
on other tasks. Note `CLAUDE.md` says the DAG columns are deliberately left unused for
now — the first version fans out one flat batch, with no dependencies between
sub-questions. Today all three tables exist and all three are empty; no Java code
reads or writes any of them.

**Hibernate/JPA** is the layer that would let Java classes stand in for those tables
automatically, so you could read and write rows without hand-writing SQL. JPA —
Jakarta Persistence API — is the standard; Hibernate is the program that carries it
out. It is on the classpath (`control-plane/pom.xml:41-44`), but configured with
`spring.jpa.hibernate.ddl-auto=validate`
(`control-plane/application.properties:5`). DDL means Data Definition Language, the
part of SQL that creates and alters tables. `validate` means: at startup, check that my
Java classes match the tables, and never, ever change the database yourself. That is the
safe setting, and the project rule is "migration first, entity second." Since zero
mapped classes exist yet, validate currently has nothing to check.

**If they vanished.** Nothing today would break, because nothing reads or writes the
database yet. All of this is scaffolding for Week 2, when the orchestration code needs
somewhere restart-proof to keep its counters.

### Redpanda (Kafka-compatible message broker, container)

**What it is.** Kafka is a message broker. A sender drops a message into a named
**topic**; readers pick it up later, at their own pace, and several different readers
can each read the same topic independently. It is not a request/reply phone call — it
is a durable drop box, and messages stay put until they expire.

**Redpanda** is a stand-in you can swap in for Kafka without changing anything else: it
speaks the same language over the network, ships as one single program file, and is
much lighter to run on a laptop.

**Analogy.** The post office. You post a letter and walk away; the recipient collects it
when they get round to it. Nobody stands waiting on a line.

**The job here.** This is where the project's design gets interesting later. The plan is
that the orchestrator posts each sub-question into `research.subtasks`, worker agents
pick them up in parallel, and post their answers into `research.findings`. Kafka is
chosen for those hops precisely because the work is slow and unpredictable — a
sub-question can take 5 to 90 seconds — and a durable drop box tolerates that where a
phone call does not.

Per `CLAUDE.md` Session 3, three topics were created by hand inside the container:
`research.subtasks`, `research.findings`, and `agent.events`. The container itself is
defined at `docker-compose.yml:31-51`.

But right now, **no Java code touches Kafka at all**. Searching the whole repo for
"kafka" outside `docker-compose.yml` returns nothing: no `spring-kafka` dependency in
any of the four `pom.xml` files, no listener, no producer.

> COULDN'T TRACE: whether the three topics currently exist inside the container. The
> Docker daemon is not running as this file is written, so `rpk topic list` cannot be
> executed to confirm. The claim that they were created comes from `CLAUDE.md`
> Session 3, not from a live check.

**If it vanished.** Today, nothing notices. It is plumbing installed ahead of the
Week 2 work.

### Actuator (`spring-boot-starter-actuator`, all 3 poms)

**What it is.** Actuator is a Spring Boot add-on that exposes a few built-in
housekeeping addresses, most importantly `/actuator/health`, which answers
`{"status":"UP"}` when the app is alive.

**Analogy.** A pulse you can take from outside without knowing any anatomy.

**The job here.** It is declared in all three service recipe cards
(`retrieval-service/pom.xml:16-19`, `agent-service/pom.xml:36-39`,
`control-plane/pom.xml:49-52`), and the project's definition of a finished session
includes all three answering UP.

One accuracy note: nothing *automated* calls it today. The three Java services are not
in `docker-compose.yml`, so no Docker healthcheck can reach them. Right now
`/actuator/health` is checked by a human with a browser, or by the IDE's dashboard.

**If it vanished.** You would lose the cheapest possible "is it up?" check, and the
Week 5 Kubernetes plan would need it back — automated restarts depend on exactly this
kind of probe.

### Tests: JUnit 5 + Mockito (via `*-test` starters)

**What it is.** JUnit is the library that finds methods marked `@Test` and runs them,
reporting pass or fail. Mockito is a mocking library: it manufactures fake stand-in
objects so you can test one class by itself, without starting the real database,
network, or its neighbour classes.

**Analogy.** JUnit is the exam invigilator. Mockito is the crash-test dummy.

**The job here.** The test libraries arrive bundled inside the `*-test` starter
dependencies (`retrieval-service/pom.xml:29-43`), which is why no test library is named
directly anywhere. `mvn dependency:tree` shows what actually lands on the classpath:
`org.junit.jupiter:junit-jupiter:6.0.3` and `org.mockito:mockito-core:5.23.0`.

Two honest corrections to the heading above. The resolved version is **JUnit 6**, not
JUnit 5 — Spring Boot 4.1.0 pulls in the newer line. And Mockito is present but
**completely unused**: no test file in this repo imports it.

`retrieval-service` currently has 17 tests across four files:

- `RetrievalServiceApplicationTests.java:10-11` — one test named `contextLoads` with an
  empty body. It looks pointless and is not: `@SpringBootTest`
  (`RetrievalServiceApplicationTests.java:6`) boots the entire application, so the test
  passes only if every bean was built and every dependency was satisfied. It is a wiring
  smoke test disguised as a blank method.
- `SourceTierResolverTest.java` — 5 tests, one per grading rule
  (`:20-43`). It builds `SourceTierResolver` with plain `new`
  (`SourceTierResolverTest.java:18`), no Spring involved, because the class only needs a
  record.
- `UrlNormalizerTest.java` — 8 tests, one per cleanup rule, plus a combined
  "kitchen sink" case (`:58-63`).
- `ExtractCacheKeyTest.java` — 3 tests (`:11-30`). The important one,
  `equivalentUrlsShareOneKey` (`:18-23`), asserts that two different spellings of one
  article produce one identical key.

`agent-service` and `control-plane` each have exactly one test, also `contextLoads`
(`AgentServiceApplicationTests.java:10-11`, `ControlPlaneApplicationTests.java:10-11`).

`CLAUDE.md` says integration tests should eventually use Testcontainers — a library that
spins up throwaway Docker containers for the duration of a test. That is not in place.
Today `contextLoads` connects to the real running containers, which is exactly why the
whole test suite fails when Docker is down.

**If they vanished.** Nothing at runtime changes. You would just lose the ability to
notice you broke something.

### `common` module

**What it is.** A fourth Maven module meant to hold the record types that more than one
service needs to agree on — the shape of a Kafka message, of a `Claim`, of a
`ResearchFinding`. It is not a service: no `main` method, nothing here ever runs on its
own. It is a plain library jar. (A jar is just a zip file full of compiled Java classes.)

**Analogy.** A shared dictionary on the table, so three people writing letters spell the
same word the same way.

**The job here.** None yet. `common/pom.xml` is 16 lines and declares no dependencies,
and there are **zero source files** underneath it — the only files in the folder are the
pom, a README, and build leftovers. The idea, spelled out in `common/README.md:17-26`,
is that if all three services import one shared `Claim` type, a field-name or type
mismatch becomes a compile error on your machine rather than a mystery failure in
production.

Note also what `common/README.md:9-15` says it must never have: the
`spring-boot-maven-plugin`. That plugin repackages a module into a self-contained
runnable jar, which quietly breaks it for use as a library. Checking `common/pom.xml`
confirms no `<build>` section at all, so the rule holds.

**If it vanished.** Today, nothing. It exists so the slot and the habit are in place
before Week 2 needs them.

> **Suspicious:** `common/README.md:10-12` states that the other three services
> "declare it as a dependency." They do not. Reading all four recipe cards shows no
> `<artifactId>common</artifactId>` dependency anywhere. Intent is not wiring.

---

## The Cast

Now the characters. They are grouped by **layer**, which simply means "what kind of job
this class does in the play." A class near the door handles arriving requests; a class
in the middle does the thinking; a class in the config layer just hands out pre-built
objects.

The "Talks to" column lists the other objects handed to this one by Spring at startup.
Those are its **dependencies** — the helpers it cannot do its job without.

### The front door layer (web/) — *receives the outside world*

| Character | Role | Talks to |
|---|---|---|
| `RetrievalController` — `web/RetrievalController.java` | The doorman at `/api/v1`. He answers all three web addresses, unpacks the arriving data into a Java object, hands it to whoever actually does the work, and packs the answer back up. He never decides anything himself — every method is one line (`RetrievalController.java:23`, `:28`, `:33`). | `SearchService`, `QuotaService` |

*(agent-service and control-plane have no door staff yet. Each building contains only
the light switch that turns the building on:
`AgentServiceApplication.java:9-11`, `ControlPlaneApplication.java:9-11`.)*

### The worker layer (service-ish classes) — *do the actual thinking*

| Character | Role | Talks to |
|---|---|---|
| `SearchService` — `search/SearchService.java` | The head librarian, and the busiest character here. For each question asked, she first checks the notepad on her desk to see if she already looked this up (`SearchService.java:54`). If yes, she reuses it, free. If no, she walks out to the newsstand, gets fresh results (`SearchService.java:62`), copies them onto the notepad for 24 hours (`SearchService.java:63`), and marks one credit spent (`SearchService.java:64-65`). Only then does she grade every result and trim the pile down to the size asked for (`SearchService.java:69-73`). | the Redis helper, the SearXNG phone line, the JSON translator, the quality grader, the meter reader |
| `QuotaService` — `quota/QuotaService.java` | The meter reader. He bumps today's counter by one every time a real search is paid for (`QuotaService.java:21-25`), and can report how many credits are left (`QuotaService.java:27-31`). He reads the meter; he does not switch off the power — see the Suspicious note below. | the Redis helper |
| `SourceTierResolver` — `tier/SourceTierResolver.java` | The quality grader. Given a web address, she pulls out the site name (`SourceTierResolver.java:19`), tidies it up by lowercasing it and dropping a leading `www.` (`:25-28`), then checks it against a list of trusted sites. Government sites score 1, big newspapers score 2, anything matching a blog pattern scores 4, and everything unrecognised gets a middling 3 (`:31-46`). Lower is better. | the trusted-site list, `SourceTierProperties` |
| `UrlNormalizer` — `url/UrlNormalizer.java` | The alias detective. Four different-looking web addresses can point at exactly one article. He rewrites all of them into one agreed spelling: lowercase the site name, drop `www.`, drop the trailing slash, drop the `#jump-to-here` part, throw away advertising tracking tags, and alphabetise whatever real settings remain (`UrlNormalizer.java:16-34`). Without him, one article would be filed under four different names. | nobody; he is a pure helper with no dependencies |
| `ExtractCacheKey` — `extract/ExtractCacheKey.java` | The label maker. He takes a web address, sends it to the alias detective, sends the result to the fingerprint clerk, keeps the first 16 characters, and glues on a prefix — producing `extract:v1:<16 characters>` (`ExtractCacheKey.java:13-15`). That is the name a future extract cache entry would be filed under. The `v1` is deliberate: to invalidate every cached page at once, you bump it to `v2` rather than deleting anything. | the alias detective, the fingerprint clerk |
| `Hashing` — `hash/Hashing.java` | The fingerprint clerk. Give him any text and he returns a fixed-length code for it (`Hashing.java:13-21`). SHA-256 — Secure Hash Algorithm, 256-bit — always returns the same code for the same input, and a code of the same length no matter how long the input was. That is why it makes a tidy storage name: no spaces, no colons, no surprises. He was deliberately lifted out of `SearchService` once a second class needed him, so the two can never drift apart. | nobody; every method is `static` |
| `ExtractorClient` — `extract/ExtractorClient.java` | The courier to the Python specialist. He packs the web address plus the page's raw HTML into a parcel, posts it to `/extract`, and unpacks the reply (`ExtractorClient.java:21-27`). He is built correctly, registered correctly, and hired by absolutely nobody — no other class in the repo calls him. Currently unemployed. | the extractor phone line |

### The config layer (config/) — *wiring and settings made into objects*

| Character | Role | Talks to |
|---|---|---|
| `SearxngClientConfig` — `config/SearxngClientConfig.java` | Builds the phone line to the newsstand once, at startup, reading the number from the settings file (`SearxngClientConfig.java:12-17`). | Spring itself |
| `ExtractClientConfig` — `config/ExtractClientConfig.java` | Same trick, for the phone line to the Python specialist (`ExtractClientConfig.java:12-17`). | Spring itself |
| `SourceTierProperties` — `config/SourceTierProperties.java` | The trusted-site list itself, turned from text into a Java object. The `source-tiers:` block in the settings file (`application.yml:1-12`) is poured into this three-field record (`SourceTierProperties.java:7`). | the settings file |
| `QuotaProperties` — `config/QuotaProperties.java` | One number, `quota.daily-limit: 1000` (`application.yml:16-17`), turned into a one-field record (`QuotaProperties.java:7`). | the settings file |

How does text in a settings file become a Java record? The label
`@ConfigurationProperties(prefix = "...")` on the record
(`SourceTierProperties.java:6`, `QuotaProperties.java:6`) says "fill me from the block
of settings under this heading." And `@ConfigurationPropertiesScan` on the startup class
(`RetrievalServiceApplication.java:8`) is what makes Spring go looking for records
carrying that label. Without that second annotation, the first one is ignored.

### The Python cast (`extractor/main.py`)

| Character | Role |
|---|---|
| `health()` — `main.py:23-25` | The pulse. Answers `{"status": "ok"}` when Docker knocks to check the container is alive. Two lines, no logic. |
| `extract()` — `main.py:28-44` | The boilerplate-stripper. Raw messy HTML in; clean article text, title, and date out, with an `OK` or `PAYWALLED` verdict attached based on whether at least 200 characters of real text survived. |

### The blueprint props (exist, unused)

Some things in this repo are real, correct, and doing nothing yet. They are props on a
stage set for an act that has not started.

The three database tables from `V1__init_schema.sql` — `runs`, `dag_nodes`,
`dag_levels` — exist and are empty. No Java class reads or writes them.

The three Redpanda topics are mail slots with no letters, and no Java code that knows
how to post one.

The `common` module is an empty toolbox with a label on the lid.

And `ExtractorClient` plus the whole Python sidecar are a courier and a specialist,
both trained and standing ready, with no work order between them.

> **Suspicious, two things.**
>
> First, `QuotaProperties.java:4` imports
> `org.springframework.context.annotation.Configuration` and then never uses it. Dead
> import. Harmless, but it hints the class was copied from a template.
>
> Second, and more interesting: nothing ever *checks* the quota before spending it.
> `SearchService.search()` (`SearchService.java:48-75`) calls `recordSpend()` on every
> cache miss but never asks `remaining()` first. And `remaining()` floors at zero
> (`QuotaService.java:30`), so once you are out of credits it calmly reports "0" forever
> while searches keep right on going. Today the quota is a fuel gauge, not a fuel
> cutoff.

---

*Next: [01-search-flow.md](01-search-flow.md) — the flagship: what actually happens in
those ~50 milliseconds between curl and JSON.*
