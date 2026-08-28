# The Story of This Codebase — 05: Boot & Compose (How Everything Wakes Up)

*Flow 6 of the inventory. No user triggers this one. The trigger is a human typing
`docker compose up -d`, and later clicking "Run" in the IDE or typing `mvn
spring-boot:run`. Every other flow in this guide depends on machinery that is built
right here, during those first few seconds. So this is the file where all the
"framework magic" gets explained from scratch.*

**On this page:** [0. In plain English](#in-plain-english-30-seconds) · [1. Trigger](#1-what-triggers-this) · [2. Wake-up diagram](#2-the-wake-up-as-a-diagram) · [3. Narration](#3-the-narration) · [4. Framework magic](#4-framework-magic-roundup-the-whole-cast-of-invisible-actors) · [5. Unhappy paths at boot](#5-unhappy-paths-at-boot) · [6. If I changed X](#6-if-i-changed-x-what-breaks)

---

## In plain English (30 seconds)

This project is not one program. It is a handful of separate programs that have to be
switched on before anything works. Some of them are pre-packaged helper programs that
run inside sealed boxes on your laptop. Others are Java programs you start yourself.

This file walks through that switch-on, second by second. By the end you will know what
a "container" actually is, why the Java code never says `new SearchService(...)` yet a
`SearchService` object still exists, where the values in the settings file turn into
Java objects, and why one of the three Java services refuses to start if the database
is switched off.

---

## 1. What triggers this

Nobody sends a request here. A human flips two ignition switches, in this order.

**Switch 1: `docker compose up -d`.**

A quick vocabulary stop, because everything below leans on these three words.

- An **image** is a frozen, read-only snapshot of a whole miniature computer: an
  operating system, the programs installed on it, and your files. Think of it as a
  recipe printed on paper.
- A **container** is one running copy of that image. Think of it as the actual meal
  cooked from that recipe. One recipe, as many meals as you like.
- The **Docker daemon** is the background program on your laptop that builds images and
  runs containers. "Daemon" just means "a program that runs quietly in the background
  and waits for orders."

**Docker Compose** is a small tool that reads one file, `docker-compose.yml`, and starts
several containers at once so you do not have to type five long commands. **YAML**
(short for "YAML Ain't Markup Language") is just a text format for settings, where
indentation shows what belongs inside what.

This project's compose file starts five containers (`docker-compose.yml:1-73`):

| Container | What it is, in one line | Where it is defined |
|---|---|---|
| `research-postgres` | The filing cabinet. A **SQL database** — a program that stores data in tables of rows and columns. SQL means "Structured Query Language", the language you talk to it in. | `docker-compose.yml:2-17` |
| `research-redis` | The notepad on the desk. A very fast in-memory store for short-lived scraps of data. The story guide calls it the card catalog. | `docker-compose.yml:19-29` |
| `research-redpanda` | The mailroom. A message broker that speaks Kafka's language, so services can post messages to each other later. Nothing in this repo talks to it yet. | `docker-compose.yml:31-51` |
| `research-searxng` | The newsstand outside. A self-hosted web search engine. | `docker-compose.yml:53-61` |
| `research-extractor` | The specialist who strips the adverts and menus off a saved web page. Written in Python. | `docker-compose.yml:64-73` |

**Switch 2: starting a Spring app**, one of the three Java services, from the IDE or with
Maven.

Here is the fact that surprises most people reading this repo for the first time. **The
three Java services are not in `docker-compose.yml` at all.** Grep the file for
"retrieval", "agent-service" or "control-plane" and you get nothing. They run directly
on your laptop as ordinary Java programs, and they reach the containers through
`localhost` — the name a machine uses for itself
(`retrieval-service/application.yml:13-14`, `retrieval-service/application.yml:19-20`,
`control-plane/application.properties:2`).

So the mental picture is: five sealed boxes running on your machine, plus up to three
Java programs standing outside those boxes, phoning in through the port numbers the
boxes have opened.

## 2. The wake-up as a diagram

A **sequence diagram** reads top to bottom as time passing. Each vertical line is one
participant, and each arrow is one participant doing something to another.

```text
Human at the keyboard
   │ types: docker compose up -d
   ▼
Docker daemon
   │ build an image from ./extractor, then run its start command
   ▼
extractor container
   │ uvicorn starts listening on port 8000
   │ Docker pokes /health every 10 seconds

Human at the keyboard
   │ starts RetrievalServiceApplication
   ▼
JVM - the running Java program
   │ SpringApplication.run scans the packages for annotated classes
   ▼
Spring container - the object factory inside the JVM
   │ builds the objects: 2 RestClients, Redis template, services, controller
   │ copies YAML settings into SourceTierProperties and QuotaProperties

JVM - the running Java program
   │ embedded Tomcat opens port 8081. Ready.

Human at the keyboard
   │ starts ControlPlaneApplication
   ▼
JVM - the running Java program
   │ SpringApplication.run (as before)
   ▼
Spring container - the object factory inside the JVM
   │ opens a database connection, then Flyway runs V1__init_schema.sql
   ▼
Postgres container
   │ Hibernate is in validate mode
   │ There are zero entity classes, so it checks nothing

JVM - the running Java program
   │ Tomcat opens port 8083. Ready.
```

And here is the smaller picture of how a recipe becomes a running program, since that
step is invisible and happens only once:

```text
extractor/Dockerfile (7 lines of recipe)
   │ docker build runs each line, saves a layer
   ▼
docker build
   │ produces one cached layer per line
   ▼
an image (frozen snapshot)
   │ docker run starts the CMD line
   ▼
a container
   │ uvicorn listening on 8000
```

## 3. The narration

### Part A — the Dockerfile recipe becomes a container

A **Dockerfile** is the recipe file. Ours is seven lines long (`extractor/Dockerfile:1-7`),
and the daemon runs it top to bottom.

Line 1 picks the starting point: `FROM python:3.12-slim` (`extractor/Dockerfile:1`). That
is a ready-made image containing a small Linux system with Python 3.12 already
installed. "slim" means the makers stripped out everything non-essential to keep it
small. Line 2 sets `/app` as the folder to work inside (`extractor/Dockerfile:2`).

Now the interesting part, and it is a genuine design decision rather than an accident.

Line 3 copies in `requirements.txt` — the shopping list of Python libraries
(`extractor/Dockerfile:3`). That list names three things: `fastapi`, `uvicorn` and
`trafilatura` (`extractor/requirements.txt:1-3`). Line 4 then runs `pip install`, the
Python package installer, which downloads and installs all three
(`extractor/Dockerfile:4`). Only *after* that, on line 5, does the recipe copy in
`main.py`, the actual program (`extractor/Dockerfile:5`).

Why that order? Because each line of a Dockerfile produces a **layer** — a saved,
frozen snapshot of the filesystem at that point, like one coat of paint on a wall.
Docker keeps those layers and reuses them. On a rebuild it re-runs a line only if that line's input changed. You will edit
`main.py` twenty times this month. You will edit `requirements.txt` almost never. With
the current order, those twenty edits reuse the cached pip layer and the rebuild takes a
second. Flip lines 3-4 and line 5, and every single code edit re-downloads all three
libraries from the internet. Same result, far slower.

Layers have a second, quieter benefit: two images that both start `FROM python:3.12-slim`
share those base layers on disk instead of each keeping a private copy.

Line 6, `EXPOSE 8000`, is documentation only (`extractor/Dockerfile:6`). It tells a human
reader "this program listens on 8000". It does not actually open anything. Line 7 is the
`CMD`, the command the container runs when it starts (`extractor/Dockerfile:7`):

```
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

`uvicorn` is the web server for Python — the part that actually listens for incoming
network calls. `main:app` means "find the variable named `app` inside `main.py`", which
is the FastAPI application object (`extractor/main.py:5`). The `--host 0.0.0.0` flag
matters more than it looks: it means "accept calls arriving on any network address, not
just from inside this box." Set it to the default and the container would only ever talk
to itself, and the port mapping below would reach nothing.

Then Compose takes over. The `extractor` block is the only one with a `build:` line
(`docker-compose.yml:65`) — the other four pull ready-made images off the internet, but
ours is built from our own recipe folder. Compose builds it the first time and reuses
the built image afterwards, unless you force a rebuild with `up --build`.

The block also maps ports (`docker-compose.yml:67-68`):

```
ports:
  - "8000:8000"
```

Read that as `hostPort:containerPort`. A **port** is a numbered door on a machine that
network traffic can arrive at. The container has its own private set of doors. This line
drills a tunnel: anything knocking on port 8000 of your laptop gets forwarded to port
8000 inside the container. That is exactly why the Java side can be configured with
`extractor.base-url: http://localhost:8000` (`retrieval-service/application.yml:19-20`)
even though the Python code is sealed inside a box.

Finally the **healthcheck** (`docker-compose.yml:69-73`). A healthcheck is a command
Docker runs *inside* the container on a timer, to decide whether the thing is actually
alive rather than merely started. Ours runs every 10 seconds, allows 5 seconds for an
answer, and gives up after 5 consecutive failures:

```
test: ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://localhost:8000/health')\""]
```

It is written in Python rather than the more usual `curl` for a concrete reason: the
`slim` base image does not ship `curl`, but it obviously ships Python. The URL it pokes,
`/health`, exists because `main.py` defines it (`extractor/main.py:23-25`).

The other four containers skip the build step entirely. Two of them are worth a closer
look.

**Redis** gets a custom startup command (`docker-compose.yml:22`):

```
command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
```

In plain English: never use more than 256 megabytes of memory, and when you hit that
ceiling, throw out the entries nobody has touched recently to make room. **LRU** stands
for "least recently used" — the eviction rule that picks the stalest entry first.
Redis's factory default is a policy called `noeviction`, which does the opposite: at the
ceiling it starts *refusing* new writes and returning errors. For a cache — where every
entry is a disposable copy of something you can always fetch again — refusing writes is
exactly the wrong failure. Hence the override.

**SearXNG** gets a folder from your laptop mounted into it (`docker-compose.yml:58-59`):

```
volumes:
  - ./searxng:/etc/searxng
```

A **volume mount** is a shared doorway between a folder on your machine and a folder
inside the container. The left side is the real folder in this repo, the right side is
where the container sees it. This is how our `searxng/settings.yml` reaches the search
engine without rebuilding its image, and that file is what turns on machine-readable
answers (`searxng/settings.yml:9-12`):

```
search:
  formats:
    - html
    - json
```

**JSON** — "JavaScript Object Notation" — is the plain-text format the Java code expects
back. Without that `json` line, SearXNG would only ever reply with a web page meant for
human eyes, and every call from `SearchService` would fail confusingly.

Postgres, for its part, is handed a database name, username and password through
**environment variables** — named values passed into a program at startup
(`docker-compose.yml:5-8`) — and a **named volume**, `postgres_data`
(`docker-compose.yml:11-12`, declared at `docker-compose.yml:75-76`). A named volume is
storage Docker manages outside the container, so the tables survive when the container
itself is deleted and recreated.

> **Trap worth its weight:** a container's startup command is fixed at the moment the
> container is created. Editing `docker-compose.yml` changes the *recipe*, not the
> already-running *meal*. Session 10 of the project log records exactly this: the live
> Redis kept reporting `noeviction` until `docker compose up -d redis` destroyed and
> recreated the container (`CLAUDE.md`, Session 10). It is the same disease as Session
> 3's actuator mystery, where a Java process kept behaving as if a newly added library
> did not exist, because a running program's list of loaded libraries is fixed when it
> launches (`CLAUDE.md`, Session 3). Running processes never re-read their birth
> certificate.

> **Suspicious:** there is no `depends_on:` anywhere in `docker-compose.yml`, and no
> `restart:` policy either. `depends_on` is the setting that says "do not start B until
> A is up". Without it, all five containers start in parallel in whatever order they
> like, and the healthchecks are purely informational — nothing waits on them. It
> happens to be fine here, because nothing in this repo talks container-to-container:
> every conversation goes laptop-to-container. It would stop being fine the moment a
> containerised Java service needed Postgres to already be listening.

> **Suspicious:** four of the five containers have a healthcheck; `searxng` has none
> (`docker-compose.yml:53-61`). So `docker compose ps` can show SearXNG as "running"
> while the search engine inside is still starting up or wedged. It is the one
> container whose green light means only "the process exists".

### Part B — a Spring service wakes up

Take `retrieval-service` as the rich example, because it has the most moving parts.

First, the plainest possible framing of what Spring even *is*. In ordinary Java you
write `SearchService s = new SearchService(a, b, c);` and you are responsible for having
`a`, `b` and `c` ready first. In a Spring application you never write that line. Instead
you mark your classes, and at startup Spring reads those marks, works out what depends
on what, builds everything in the right order, and keeps one copy of each object in a
big registry. **An object that Spring built and holds for you is called a "bean".** The
registry itself is called the **application context**, or informally the Spring
container. Handing objects their dependencies instead of making them fetch their own is
called **dependency injection**.

A **marker** in Java source is written with an `@` sign and is called an **annotation**.
An annotation does nothing by itself. It is a sticker. Something else — here, Spring —
reads the stickers and acts on them.

The whole thing starts from a completely ordinary `main` method
(`RetrievalServiceApplication.java:11-13`):

```java
public static void main(String[] args) {
    SpringApplication.run(RetrievalServiceApplication.class, args);
}
```

The **JVM** — "Java Virtual Machine", the program that actually runs compiled Java —
calls `main`, and `main` calls `SpringApplication.run`. Everything below happens inside
that single call, roughly in this order.

The class wears the sticker `@SpringBootApplication` (`RetrievalServiceApplication.java:7`),
and that sticker's first job is **component scan**: "start scanning from my own package,
then search every package beneath it." Our starting package is
`com.comeback.researchplatform.retrievalservice`, so Spring walks the whole tree under
it and collects every class wearing a sticker it recognises. Think of it as a new
teacher walking a corridor and jotting down every student wearing a name tag:

| Class it finds | Sticker | Line | What the sticker means |
|---|---|---|---|
| `RetrievalController` — the doorman | `@RestController` | `RetrievalController.java:10` | "I handle incoming web requests, and my return values are the reply body." |
| `SearchService` — the librarian | `@Service` | `SearchService.java:23` | "I hold business logic. Build one of me." |
| `QuotaService` — the meter reader | `@Service` | `QuotaService.java:12` | Same as above. |
| `SourceTierResolver` — the quality grader | `@Component` | `SourceTierResolver.java:8` | The generic "build one of me". `@Service` is just a more descriptive flavour of it. |
| `ExtractorClient` — the courier | `@Component` | `ExtractorClient.java:11` | Same. |
| `SearxngClientConfig` | `@Configuration` | `SearxngClientConfig.java:9` | "I am not a worker. I am a small factory: read my `@Bean` methods and keep what they return." |
| `ExtractClientConfig` | `@Configuration` | `ExtractClientConfig.java:9` | Same. |

Note what the scan deliberately does **not** pick up. `UrlNormalizer` — the alias
detective (`UrlNormalizer.java:8`), `Hashing` — the fingerprint clerk (`Hashing.java:8`),
and `ExtractCacheKey` — the label maker (`ExtractCacheKey.java:6`) are all plain
`public final class` with no annotation at all. They hold only static methods, so
nobody needs an instance of them, so Spring never builds one. That is the right call:
a bean you never need is just startup cost.

After the scan, Spring moves to **auto-configuration**: it looks at the **classpath** —
simply the list of code libraries available to the running program, which Maven
assembles from the `<dependency>` entries in the module's `pom.xml` — and configures the
obvious thing without being asked. A **starter** is nothing magic, by the way: it is an
empty package whose only content is a curated list of other packages, so one line in
your `pom.xml` pulls in a whole coherent set.

The shopping list for this module triggers three decisions, one after another.
`spring-boot-starter-data-redis` is on it (`retrieval-service/pom.xml:20-23`), so Boot
builds a `StringRedisTemplate` bean — the object the code uses to read and write
Redis — backed by a connection factory using Lettuce, the Redis client library that
starter brings along. This is Spring Boot library behaviour, not code in this repo; no
file here declares that bean. Importantly, the actual network connection to Redis is
not opened yet. It waits until the first real command.

Then `spring-boot-starter-webmvc` is on the list (`retrieval-service/pom.xml:24-27`), so
Boot prepares an **embedded Tomcat**. Tomcat is a web server; "embedded" means it runs
inside your own program rather than being a separate piece of software you install and
deploy into. The same starter brings the JSON library, which is why `SearchService` can
ask for an `ObjectMapper` (`SearchService.java:29`) even though nothing in this repo
creates one — the only two `@Bean` methods in the entire codebase are the two
`RestClient` factories.

And `spring-boot-starter-actuator` is on the list (`retrieval-service/pom.xml:16-19`), so
Boot registers `/actuator/health`, a built-in URL that answers `{"status":"UP"}`. The
project log records the confirming startup line, `Exposing 1 endpoint beneath base
path '/actuator'` (`CLAUDE.md`, Session 3).

With the bean list settled, Spring moves to **dependency injection** — it solves the
jigsaw, innermost piece first. It knows what each thing asks for, so it builds
anything with no dependencies first, then whatever those unlock. **Constructor
injection** is the style used everywhere in this repo, and it just means "a class
declares what it needs as constructor parameters, and Spring supplies them." There is
no `@Autowired` sticker on any field. That is deliberate: it lets every field be
`final`, makes the dependency list impossible to miss, and lets a plain unit test build
the object with `new`.

The two `RestClient` beans come first, because they need nothing but a text setting
(`SearxngClientConfig.java:12-16`):

```java
@Bean
public RestClient searxngRestClient(@Value(("${searxng.base-url}"))String baseUrl) {
    return RestClient.builder()
            .baseUrl(baseUrl)
            .build();
}
```

`RestClient` is Spring's object for *making* outgoing web calls, as opposed to receiving
them. `@Value("${searxng.base-url}")` says "look up the setting named `searxng.base-url`
and pass its text here" — which resolves to `http://localhost:8080`
(`retrieval-service/application.yml:13-14`). `ExtractClientConfig` does the identical
dance for `extractor.base-url` → `http://localhost:8000`
(`ExtractClientConfig.java:12-16`, `retrieval-service/application.yml:19-20`).

**And here is the sharpest edge in the whole boot sequence.** Spring's normal way of
picking a bean to inject is *by type*: "you asked for a `RestClient`, here is the
`RestClient`." That works right up until there are two of them. The moment
`ExtractClientConfig` was added, there were two `RestClient` beans, and Spring could no
longer choose. Notice the shape of that failure: adding a brand-new class broke the
*already-working* injection in `SearchService`, which had not been touched.

The fix is `@Qualifier`, which names the exact bean wanted. It goes on the **constructor
parameter**, not on the class and not on the constructor itself — that is not legal Java
here (`SearchService.java:33`, `ExtractorClient.java:16`):

```java
public SearchService(SourceTierResolver sourceTierResolver,
                     @Qualifier("searxngRestClient") RestClient searxngRestClient,
                     StringRedisTemplate stringRedisTemplate,
                     ObjectMapper objectMapper,
                     QuotaService quotaService) {
```

The name in the quotes is the `@Bean` method's own name (`SearxngClientConfig.java:13`),
because Spring names a bean after the method that produced it.

With the clients built, the rest falls out in order: `SourceTierResolver` takes its
settings record (`SourceTierResolver.java:13-16`), `QuotaService` takes the Redis
template and its settings record (`QuotaService.java:17-20`), `SearchService` takes its
five arguments (`SearchService.java:33-39`), and finally the doorman takes the two
services it delegates to (`RetrievalController.java:16-19`).

As it builds those beans, Spring also fills in the **settings records**. The sticker
`@ConfigurationPropertiesScan` sits next to the main annotation
(`RetrievalServiceApplication.java:8`), and it tells Spring: also go hunting for records
marked `@ConfigurationProperties`, and fill them from the settings files.

Two records qualify. `SourceTierProperties` claims the prefix `source-tiers`
(`SourceTierProperties.java:6-7`) and receives the three domain lists the grader uses
(`retrieval-service/application.yml:1-12`). `QuotaProperties` claims the prefix `quota`
(`QuotaProperties.java:6-7`) and receives the single number `1000`
(`retrieval-service/application.yml:16-17`).

One subtlety earns its own paragraph. The YAML key is spelled `tier4-patterns` with a
hyphen (`retrieval-service/application.yml:10`), while the Java record component is
spelled `tier4Patterns` with a capital P (`SourceTierProperties.java:7`). Those match
because of **relaxed binding**: Spring strips punctuation and case differences before
comparing names, so `tier4-patterns`, `tier4Patterns` and `TIER4_PATTERNS` are all the
same key to it. Convenient — and, as section 5 shows, quietly dangerous, because a key
that is genuinely misspelled simply matches nothing instead of complaining.

> **Suspicious (carried over from 00-the-world):** `QuotaProperties.java:4` imports
> `org.springframework.context.annotation.Configuration` and never uses it. Harmless
> dead import, but it hints someone once expected `@Configuration` to be needed here. It
> is not — `@ConfigurationPropertiesScan` is what makes this record a bean.

Finally, the embedded web server binds to a port and starts listening.
`server.port=8081` picks which one (`retrieval-service/application.properties:2`). From
this instant the doorman is reachable and flow 01 becomes possible.

Note carefully what boot did **not** do. It did not connect to Redis. It did not call
SearXNG. It did not pre-load any cache, or touch the extractor. The service wakes up
armed, not busy — every external connection is made lazily, on the first request that
actually needs it.

### Part C — control-plane wakes up differently (and harder)

The main class is the same three-line skeleton (`ControlPlaneApplication.java:6-11`).
The difference is entirely in what its `pom.xml` drags onto the classpath, which changes
what auto-configuration decides to do.

Its shopping list adds JPA (`control-plane/pom.xml:41-44`), Flyway
(`control-plane/pom.xml:45-48`), the Flyway plug-in for Postgres
(`control-plane/pom.xml:54-57`), and the PostgreSQL driver — the library that knows how
to speak Postgres over the network (`control-plane/pom.xml:58-62`). Its settings point at
the real database (`control-plane/application.properties:2-4`). Boot gains three extra
stages, and they run in a strict order.

First the **connection pool opens, eagerly**. A **connection pool** is a small set
of already-open database connections kept ready, because opening one takes far longer
than using one. Unlike Redis, this happens during startup, not on first use — Flyway
needs a live connection immediately. If the Postgres container is down, startup fails
outright. The project log records this exact failure being briefly mistaken for a code
bug: the test `contextLoads` died with `Connection to localhost:5432 refused`, and the
real cause was that Docker was not running (`CLAUDE.md`, Session 4). A different and
earlier failure, `Failed to determine a suitable driver class`, was the same test
complaining that no datasource settings existed yet at all (`CLAUDE.md`, Session 2).

With a live connection in hand, **Flyway runs the migrations**. **Flyway** is a tool
that keeps a database's structure in version-controlled `.sql` files and applies them
in order. A **migration** is one such file. Flyway keeps its own bookkeeping table,
`flyway_schema_history`, listing what it has already applied. On the very first boot
that table does not exist, so Flyway creates it, sees that migration `V1` has never
run, and executes `V1__init_schema.sql`. That file creates three tables: `runs`
(`V1__init_schema.sql:1-8`), `dag_nodes` (`V1__init_schema.sql:10-20`) and `dag_levels`
(`V1__init_schema.sql:23-30`), plus an index on each of the latter two
(`V1__init_schema.sql:21`, `V1__init_schema.sql:32`). An **index** is a lookup shortcut
that makes searching a column fast. On every later boot, Flyway does not re-run
anything. It compares a **checksum** — a short fingerprint computed from the file's
contents — against the one it stored. Same fingerprint, nothing to do. Different
fingerprint, it refuses to start, on the grounds that someone edited history.

Then, once the tables actually exist, **Hibernate validates**. **JPA** ("Jakarta
Persistence API") is the Java standard for mapping database rows onto Java objects;
**Hibernate** is the implementation of it that Spring Boot uses. The setting
`spring.jpa.hibernate.ddl-auto=validate` (`control-plane/application.properties:5`) tells
Hibernate: at startup, compare my Java classes against the real tables and shout if they
disagree — but never, ever change the database yourself. That is the project's
"migration first, entity second" rule enforced mechanically.

Today it validates precisely nothing, because there are zero `@Entity` classes in
control-plane. The three tables sit there empty and untouched: stage props waiting for
Week 2's play. Tomcat then opens 8083 (`control-plane/application.properties:6`).

`agent-service`, by contrast, boots the same way as retrieval-service minus everything
interesting. No Redis, no settings records, no outbound clients — just the web and
actuator starters (`agent-service/pom.xml:31-46`), a bare main class
(`AgentServiceApplication.java:6-11`), and two lines of settings giving it a name and
port 8082 (`agent-service/application.properties:1-2`).

## 4. Framework magic roundup: the whole cast of invisible actors

Every row here is work that happens without a single line of this project's code asking
for it.

| The invisible thing | Who actually does it | When |
|---|---|---|
| Reading the five `pom.xml` files and building the modules in the right order — `common`, then the three services (`pom.xml:11-16`) | **Maven**, the build tool | whenever you run `mvn` |
| Deciding every library's version number from one imported list, so module poms name dependencies without versions (`pom.xml:22-32`) | Maven, via `<dependencyManagement>` | build time |
| Turning the 7-line recipe into an image, one cached layer per line | The Docker daemon | first `docker compose up`; reused after |
| Running the little "are you alive" commands inside containers | The Docker daemon | every 5s for four containers, every 10s for the extractor; `searxng` has none |
| Finding annotated classes and building one object of each — those objects are called **beans** | The Spring container, during `SpringApplication.run` | boot |
| Guessing what to configure from what is on the classpath — **auto-configuration** | Spring Boot | boot |
| Passing each object the other objects it asked for in its constructor — **dependency injection** | The Spring container | boot |
| Choosing between two same-typed beans by name — `@Qualifier` disambiguation | The Spring container | boot |
| Copying YAML settings into records, ignoring hyphens and capitals — **relaxed binding** | Spring Boot's property machinery, switched on by `@ConfigurationPropertiesScan` (`RetrievalServiceApplication.java:8`) | boot |
| Creating the three database tables the first time, then verifying the files have not changed | Flyway, on datasource startup — control-plane only | boot |
| Opening a numbered network door and listening on it | embedded Tomcat for the Java services; uvicorn for the Python one | end of boot |

## 5. Unhappy paths at boot

1. **Postgres is down and you start control-plane.** Flyway needs a connection during
   startup, cannot get one, and the application exits with a non-zero code rather than
   limping along (`CLAUDE.md`, Session 4). State afterwards: clean. Nothing was
   half-created, because Flyway wraps each migration in a transaction — an all-or-nothing
   unit of work — and Postgres can roll back table creation.
2. **Redis is down and you start retrieval-service.** It boots perfectly happily. The
   connection is lazy, so the failure is postponed to the first `/api/v1/search` call —
   see flow 01 §5.3. COULDN'T TRACE: no setting in this repo forces an eager Redis
   connection at boot; this is the client library's default behaviour, not a choice
   recorded anywhere in the code.
3. **The port is already taken.** Tomcat or uvicorn tries to claim the door, finds
   someone standing in it, and the process dies immediately. This really happened: none
   of the three Java apps set `server.port` at first, so all three defaulted to 8080 and
   collided with the SearXNG container's own 8080 mapping (`docker-compose.yml:56-57`).
   The fix was giving each service its own number — 8081, 8082, 8083
   (`retrieval-service/application.properties:2`, `agent-service/application.properties:2`,
   `control-plane/application.properties:6`) (`CLAUDE.md`, Session 3).
4. **A misspelled YAML key.** Relaxed binding forgives hyphens and capitals, but it
   cannot forgive a genuinely different word. Write `tier4:` where the record component
   is `tier4Patterns` and nothing matches, so that list is left as `null` while `tier1`
   and `tier2` bind fine. Boot succeeds. The bomb goes off later, at the first URL the
   grader inspects, when the loop over `tierProperties.tier4Patterns()` dereferences null
   (`SourceTierResolver.java:39`). The key really was written as `tier4` at first and
   renamed to `tier4-patterns` to fix exactly this (`CLAUDE.md`, Session 4). COULDN'T
   TRACE: whether an actual `NullPointerException` was ever observed, or whether the
   rename landed before anyone hit it — the log does not say. Boot-time validation of
   properties would turn this into a startup error; none is configured.
5. **The extractor image fails to build.** If `pip install` cannot reach the internet or
   a version constraint in `extractor/requirements.txt:1-3` cannot be satisfied, the
   build stops and no container is ever created. Compose reports a build failure, and the
   other four containers — having no `depends_on` relationship to it — come up normally.
   The Java side then fails only if something calls the courier, which today nothing does.

## 6. If I changed X, what breaks?

1. **Move `COPY main.py .` above the pip lines in `extractor/Dockerfile`.** Nothing
   breaks functionally; the container behaves identically. But the cached layer for
   `pip install` is now invalidated by every code edit, so every rebuild re-downloads
   FastAPI, uvicorn and trafilatura (`extractor/Dockerfile:3-5`). A tax you pay weekly
   without ever noticing you are paying it.
2. **Delete `@ConfigurationPropertiesScan`** (`RetrievalServiceApplication.java:8`).
   `SourceTierProperties` and `QuotaProperties` stop being beans. Then
   `SourceTierResolver` and `QuotaService` cannot be constructed, which means
   `SearchService` cannot be constructed, which means the controller cannot be
   constructed. Boot dies with an unsatisfied-dependency error. The good news: the error
   message names the exact missing type, so this is a loud, five-second fix.
3. **Delete the `@Qualifier` from `SearchService`'s constructor parameter**
   (`SearchService.java:33`). Two `RestClient` beans exist
   (`SearxngClientConfig.java:13`, `ExtractClientConfig.java:13`) and Spring has no way
   to pick. Boot fails with an ambiguity error at the point of building `SearchService`.
   Worse than failing would be succeeding: had Spring guessed, the librarian would be
   sending search queries to the Python extractor.
4. **Edit Redis's compose command without recreating the container.** The file now says
   `allkeys-lru`, the live server still says `noeviction`, and `docker compose ps` shows
   a perfectly green row. The cache then silently starts rejecting writes once it reaches
   256MB. Trust nothing but the server's own answer:
   `redis-cli CONFIG GET maxmemory-policy` (`docker-compose.yml:22`; `CLAUDE.md`,
   Session 10).
5. **Add an `@Entity` class to control-plane whose fields do not match V1's columns.**
   Hibernate's validate mode compares them and refuses to start
   (`control-plane/application.properties:5`). This is working as intended, not a bug:
   the migration-first rule converts schema drift into a loud startup error instead of a
   quiet 2am data surprise.
6. **Set retrieval-service's port back to 8080** (`retrieval-service/application.properties:2`).
   It collides with the SearXNG container's published port (`docker-compose.yml:56-57`).
   Whichever starts second loses. If it is SearXNG that loses, searches break in a way
   that looks nothing like a port problem.

> **Suspicious (carried over from 00-the-world, re-verified):** the root `pom.xml` sets
> `<java.version>21</java.version>` (`pom.xml:18-20`) but never wires that value into the
> compiler. Only `retrieval-service/pom.xml:45-48` adds
> `<maven.compiler.release>${java.version}</maven.compiler.release>`. So today only
> retrieval-service provably compiles as Java 21. `agent-service/pom.xml:28-30` and
> `control-plane/pom.xml:27-29` declare the property but leave it inert. They compile
> fine right now because each contains one trivial class, but the first `record` written
> in either may fail with a baffling "records are not supported in -source 8" error. This
> exact trap already bit retrieval-service once (`CLAUDE.md`, Session 4).
