# The Story of This Codebase — 01: The Search Flow

*Flow 1 of the inventory (including its cache-hit twin, flow 3). This is the only
flow in the repo where data travels a long road: HTTP → Java → Redis → out to the
internet → back through all of it again.*

**On this page:** [Plain English](#in-plain-english-30-seconds) · [1. Trigger](#1-what-triggers-this) · [2. Diagram](#2-the-whole-journey-as-a-diagram) · [3. Narration](#3-the-narration-step-by-step) · [4. Framework magic](#4-framework-magic-roundup-who-calls-what-unseen) · [5. Unhappy paths](#5-unhappy-paths-when-it-breaks-where-and-what-state-remains) · [6. If I changed X](#6-if-i-changed-x-what-breaks)

---

## In plain English (30 seconds)

You send a question to our program. The program first checks its own notepad to see
if it already asked that exact question recently. If the answer is on the notepad,
it hands it straight back — free and instant. If not, it goes out and asks a real
search engine, writes the answer on the notepad, and marks down that it spent one
"credit". Either way it then ranks the links by how trustworthy the website is,
throws away the weak ones, keeps only as many as you asked for, and sends them back.

After reading this page you will know exactly which piece of code does each of those
things, which line it lives on, and what happens when each piece breaks.

## 1. What triggers this

Someone wants search results for a question. Today that someone is you, typing a
command in a terminal. Later it will be a "researcher" robot inside another service.
Say the question is *"RBI inflation policy 2026"*.

They send one HTTP request to `http://localhost:8081/api/v1/search`.

HTTP stands for HyperText Transfer Protocol. It is just the agreed way one program
talks to another over a network: you send a chunk of text, you get a chunk of text
back. A `POST` request is the flavour of HTTP request that carries a body — a lump of
data going *to* the server, rather than just asking for a page.

The body is written in JSON — JavaScript Object Notation, a plain-text way of writing
down data using `{ }`, `[ ]`, names and values. It looks like this:

```json
{ "queries": ["RBI inflation policy 2026"], "maxResults": 10, "freshness": null, "minTier": 3 }
```

That shape is not accidental. It matches a Java `record` called `SearchRequest`
(`dto/SearchRequest.java:6`). A `record` is a short way to declare a class that just
holds values — Java writes the constructor and the getters for you. This one holds
exactly four things and nothing else:

| Field in the JSON | Java type | What it means in plain words |
|---|---|---|
| `queries` | `List<String>` | One or more question strings. You may send several at once. |
| `maxResults` | `int` | The most links you want back, in total. |
| `freshness` | `String` | How recent the results should be. Accepted, but not yet sent onward — more on this below. |
| `minTier` | `int` | A quality floor. A "tier" is a trust grade for a website — 1 is best, 4 is worst (`tier/SourceTierResolver.java:31-46`). See the full explanation in [step 3](#step-3-grading-and-trimming). |

Port `8081` is where this service listens. That number is set in one line of
configuration (`application.properties:2`). A "port" is like a numbered door on your
machine: many programs share one computer, so each picks a different door number.

## 2. The whole journey as a diagram

Read this top to bottom. Each arrow is one hand-off from one participant to the next.
The names in the diagram are the characters this guide uses throughout: the doorman,
the librarian, the notepad, the meter reader, the newsstand.

```text
The cast:
  U  You, at a terminal
  T  Tomcat worker thread
  C  RetrievalController, the doorman
  S  SearchService, the librarian
  R  Redis, the notepad
  Q  QuotaService, the meter reader
  X  SearXNG, the newsstand

U ─► T   POST /api/v1/search carrying JSON text
      │   (T,C) Jackson turns the JSON text into a SearchRequest object
      ▼
T ─► C   calls search, handing it the request
      │
      ▼
C ─► S   calls search, handing over the whole job
      │
      ▼
repeat: once for every query string in the list
      │
      ▼
S ─► S   build a cache key for this exact search
      │
      ▼
S ─► R   GET search:v1:searxng:abc123...
      │
      ├── cache HIT: the notepad has it
      │      R ─► S   the stored JSON text
      │      (S) Jackson turns it back into objects, cacheHits goes up by 1
      │
      └── cache MISS: the notepad is blank
             S ─► X   GET /search with the query, asking for JSON
             X ─► S   raw results as JSON
             S ─► R   SET the key, keep it for 24 hours
             S ─► Q   recordSpend - add 1 to today's counter
      │
      ▼
back together: HIT and MISS rejoin here
      │
      ▼
after the last query
      │
      ▼
S ─► S   grade each link, drop weak ones, cut the list short
      │
      ▼
S ─► C   a SearchResponse holding results, creditsSpent, cacheHits
      │
      ▼
C ─► T   returns that object untouched
      │   (T,U) Jackson turns the object back into JSON text
      ▼
U ─► U   prints the response
```

And here is the same cache idea on its own, because it is the one pattern you must
understand before the rest makes sense. Its name is **cache-aside**.

```text
A query arrives
      │
      ▼
Build a short label for this exact search
      │
      ▼
Is that label on the notepad?
      │
      ├── Yes (HIT): Read the saved answer. Cost: nothing───┐
      │                                                     │
      └── No (MISS): Ask the real search engine             │
      │                                                     │
      ▼                                                     │
      Write the answer on the notepad                       │
      │   (with a 24 hour expiry)                           │
      ▼                                                     │
      Add 1 to today's credit counter───────────────────────┤
                                                            ▼
                                                Continue with the results
```

"Cache" just means a small, fast store of answers you already worked out. "Cache-aside"
means *the application itself* does the checking and the saving. Nothing magic reaches
in and does it for you. You look aside at the notepad first, and you write on the
notepad afterwards. Every arrow in that picture is a line of our own Java code, and
that is deliberate — the project plan explicitly bans the shortcut annotation and asks
for the pattern to be hand-written once (`docs/PLAN.md:77-81`).

## 3. The narration, step by step

### Step 0: who is even listening? *(framework magic)*

Nobody in this codebase writes "start a web server". So who is listening on port 8081?

The answer is Spring Boot. Spring is a big Java library that assembles your program for
you; Spring Boot is the part of it that also brings a web server along. When the one
line `SpringApplication.run(...)` ran (`RetrievalServiceApplication.java:11-13`), Spring
Boot started an embedded web server called Tomcat — "embedded" meaning it runs inside
our own program rather than being installed separately. Tomcat opened port 8081
(`application.properties:2`) and has been sitting there ever since, waiting.

Now the important bit, and the one beginners most often miss: **who actually calls your
controller method?** A *thread*.

A thread is one worker following one list of instructions. Your program can have many
threads, all stepping through code at the same time. Tomcat keeps a whole team of them
ready and idle — a "thread pool", like a team of waiters standing by a restaurant door.
When a request arrives, one free waiter picks it up, carries it all the way through your
code, delivers the answer, and goes back to standing by the door.

Three consequences follow, and they matter later:

1. Your code never creates that thread and never sees it. It is handed to you.
2. One request equals one thread, start to finish. Your controller and your service run
   on the *same* worker.
3. Two requests arriving at the same moment run your code **twice, simultaneously, on
   two different workers**. Hold on to that fact — it is the whole basis of the
   [stampede problem](#the-stampede-what-two-threads-do-to-one-missing-key) in
   section 5.

How did Tomcat know that `/api/v1/search` belongs to our method? Three annotations. An
annotation is a `@`-prefixed label you stick on Java code; it does nothing by itself, but
tools read it and act on it. Here:

- `@RestController` (`RetrievalController.java:10`) — "this class answers web requests,
  and whatever my methods return should be sent back as the response body".
- `@RequestMapping("/api/v1")` (`RetrievalController.java:11`) — "every address in this
  class starts with `/api/v1`".
- `@PostMapping("/search")` (`RetrievalController.java:21`) — "this method handles POST
  requests to `/search`".

At startup Spring reads those labels once and writes a routing table: *path plus verb →
this method*. At request time there is no searching or guessing. It is a plain table
lookup, and then a method call.

### Step 1 — the doorman unwraps the package

Tomcat is holding raw bytes off the network. Those bytes are text, not Java objects.

Before your method runs, Spring notices the parameter is marked
`@RequestBody SearchRequest request` (`RetrievalController.java:22`). `@RequestBody`
means "take the body of the HTTP request and turn it into this Java type for me". Spring
hands that job to Jackson, the library that converts between JSON text and Java objects.
Jackson matches JSON field names to record component names, letter for letter and case
for case.

If a field name is misspelled or a value has the wrong type, the request fails with an
HTTP 400 status ("Bad Request") before a single line of our code runs. If it parses
cleanly, the worker thread calls:

`RetrievalController.search()` — `RetrievalController.java:22-24`

```java
public SearchResponse search(@RequestBody SearchRequest request){
    return searchService.search(request);
}
```

One line. That is the doorman's entire personality. Take the sealed envelope, hand it
unopened to `searchService.search(...)`, wait, hand back whatever comes out. He does not
know what "queries" means and never will.

> ### 🔁 HAND-OFF #1 — the web edge hands the work to the business logic
>
> **From:** `RetrievalController` (the doorman) — `RetrievalController.java:23`
> **To:** `SearchService` (the librarian) — `SearchService.java:48`
> **What physically crosses:** one `SearchRequest` object. That is it. Same computer,
> same thread, same memory — this is an ordinary Java method call, no network involved.
>
> **What each side is allowed to know:**
>
> | Side | Knows about | Deliberately knows nothing about |
> |---|---|---|
> | Controller | HTTP, JSON, addresses, status codes | caching, credits, tiers, search engines |
> | SearchService | caching, credits, tiers, search engines | HTTP, status codes, who sent this |
>
> **Why the split is worth the trouble:** tomorrow a robot living inside a *different*
> service could call `SearchService.search(...)` directly, with no HTTP anywhere in
> sight, and the librarian would not notice or care. Business logic that knows about
> HTTP can only ever be reached over HTTP.

*(A quiet piece of framework magic: how did the field `searchService` get filled in? Look
at the constructor — `RetrievalController.java:16-19`. It asks for a `SearchService` and
a `QuotaService` as parameters. At startup, Spring built those two objects, saw the
constructor wanted them, and passed them in. This is called **constructor injection**.
There is no `new SearchService()` anywhere, no lookup, no global registry you touch. It
happened once, at boot, and never again. Spring calls an object it built and manages a
**bean**.)*

### Step 2 — the librarian takes over

`SearchService.search()` — `SearchService.java:48`.

The librarian opens a ledger with three local variables (`SearchService.java:49-51`):

```java
List<SearxngResult> rawResults = new ArrayList<>();  // everything found, unsorted
int creditsSpent = 0;                                // how many real searches we paid for
int cacheHits = 0;                                   // how many answers came off the notepad
```

Then the loop begins: **for each query string** in `request.queries()`
(`SearchService.java:52`). One POST can carry several questions. Each is handled
independently, one after the other, on the same worker thread — no parallelism here yet.

#### Step 2a — minting the cache key

`cacheKey(request, query)` — `SearchService.java:77-81`.

A "cache key" is the label you write on the notepad entry so you can find it again. Get
the label wrong and everything downstream is wrong: too fussy a label and you never find
your own notes; too sloppy a label and two different searches share one note and lie to
each other.

The method does three things in order.

**One: tidy the question.** `normalizeQuery()` (`SearchService.java:83-87`) trims blank
space off both ends, lowercases everything, and squeezes runs of spaces down to one:

```java
return query.trim()
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ");
```

So `"RBI   policy "` and `"rbi policy"` become the same text and share one notepad entry.
`Locale.ROOT` (`SearchService.java:85`) pins the lowercasing rules to a fixed,
language-neutral setting, so the same input gives the same output on every machine.

**Two: glue on the freshness value.** `String raw = normalizeQuery(query) + "|" + freshness`
(`SearchService.java:79`), where a missing (`null`) freshness becomes an empty string
first (`SearchService.java:78`). The `|` is just a separator so `"ab" + "c"` and
`"a" + "bc"` cannot accidentally produce the same combined text.

**Three: fingerprint it.** The combined text is fed to `Hashing.sha256Hex()`
(`hash/Hashing.java:13-21`), and the first 16 characters of the result are kept, with a
fixed prefix glued on the front (`SearchService.java:80`):

```java
return "search:v1:searxng:" + Hashing.sha256Hex(raw).substring(0,16);
```

SHA-256 stands for Secure Hash Algorithm, 256-bit. A hash function takes any text and
produces a fixed-length scramble of it. Same input always gives the same scramble;
different input almost certainly gives a different one; you cannot run it backwards to
recover the original. `Hashing` is the guide's fingerprint clerk: he takes anything, and
hands back a fixed-size fingerprint. Internally he asks Java's built-in
`MessageDigest` for the algorithm (`hash/Hashing.java:15`), digests the text as raw
bytes (`hash/Hashing.java:16`), and formats those bytes as hexadecimal — base-16 text
using the characters `0`–`9` and `a`–`f` (`hash/Hashing.java:17`).

**Now the point beginners always ask about: why hash at all?**

Not for security, and not for correctness. Purely for tidiness. Redis keys should be
short, fixed-length, free of spaces, and free of stray `:` characters that would clash
with the `prefix:prefix:prefix` naming convention. A raw question like
`"rbi policy: 2026 outlook"` is none of those things. Its fingerprint,
`3f9a1c...`, is all of them.

**The correctness lives one step earlier — in what you feed the hasher.** Whatever
changes the answer must be inside `raw`, and whatever cannot change the answer must be
left out. That is the whole rule.

That rule is why `freshness` is in there (`SearchService.java:78-79`) even though this
service does not yet send freshness onward to the search engine. Freshness maps to the
search engine's "only results from the last week" style filter, so it *would* change the
answer the moment anyone wires it up (`docs/PLAN.md:49-51`). If it were missing from the
label, a "last 24 hours" search and an "all time" search would collide on one notepad
entry and serve each other's results. Fixing the label *before* building the feature is
cheap; discovering the collision afterwards is not.

The same rule, running the other way, is why `maxResults` and `minTier` are deliberately
**not** in the label. Neither one can change what the search engine sends back — the
search engine has no idea what our tiers are, and it ignores any "give me only N results"
request. Both are applied by our own code *after* the notepad read. Baking them into the
label would mean fetching byte-for-byte identical data twice and paying twice. That
reasoning is written down as a recorded change to the original plan
(`docs/PLAN.md:42-51`).

The `v1` in the prefix is a version number for the key format. If the format ever changes
in a way that makes old entries wrong, you bump it to `v2` and every old entry becomes
unreachable and quietly expires on its own. That is much safer than trying to hunt down
and delete old entries.

#### Step 2b — ask the card catalog first

```java
String cachedJson = stringRedisTemplate.opsForValue().get(key);
```
`SearchService.java:54`

Redis is a separate program that stores values under keys and keeps them in memory, which
makes it extremely fast. Think of it as a notepad on the librarian's desk, or a card
catalog: you look up a card by its label, you read what is written, or you find nothing.
It is not a database with tables and queries. It is a very fast box of labelled strings.

`StringRedisTemplate` is Spring's thin Java wrapper over Redis commands
(`SearchService.java:28`, injected at `SearchService.java:33`). The call
`.opsForValue()` just picks the family of commands that deal with plain string values —
Redis also has families for lists, sets and so on. Then `.get(key)` sends Redis the actual
command `GET search:v1:searxng:abc123...`.

Where does it connect to? Nothing in this repo sets a Redis address — there is no
`spring.data.redis.*` line in either config file (`application.properties:1-3`,
`application.yml:1-20`). So Spring Boot uses its own built-in default of `localhost`,
port 6379. That happens to be exactly the port docker-compose publishes for the Redis
container (`docker-compose.yml:23-24`), which is why it works with no configuration at
all.

> ### 🔁 HAND-OFF #2 — our Java program talks to a different program
>
> **From:** `SearchService` — `SearchService.java:54`
> **To:** the Redis server, a separate process, reached over the network
> **What physically crosses:** one short text command containing one key. It travels over
> TCP — Transmission Control Protocol, the ordinary reliable network plumbing — to
> `localhost`, meaning "this same machine".
> **What comes back:** either `null`, meaning "I have never heard of that key", or the
> exact JSON text we ourselves stored earlier.
>
> **Who understands what:** Redis understands *nothing* about search. To Redis this is a
> meaningless label pointing at a meaningless blob of text. All the meaning — that the
> blob is a list of results, that the label encodes a question — lives only on the Java
> side. This is a real boundary: a crash, a timeout, or a wrong answer can happen here in
> a way it cannot happen inside a plain method call.
>
> One detail worth knowing: the Redis client library used here is called Lettuce, pulled
> in by `spring-boot-starter-data-redis` (`retrieval-service/pom.xml:20-23`). It keeps
> the network connection open and reuses it rather than dialling fresh on every command.
> COULDN'T TRACE the exact connection-reuse policy — it lives inside Spring Data Redis,
> not in this repo. What this repo *does* show is that no connection-pool library
> (`commons-pool2`) is declared anywhere in `retrieval-service/pom.xml:15-44`.

#### Step 2c — HIT branch

A "cache hit" means the notepad had the answer. The check is one line
(`SearchService.java:57`):

```java
if (cachedJson != null) {
    queryResults = objectMapper.readValue(cachedJson, new TypeReference<List<SearxngResult>>() {});
    cacheHits++;
}
```

What arrived from Redis is text. It must become Java objects again. `objectMapper` is
Jackson's converter (`SearchService.java:29`), and `readValue` parses the text
(`SearchService.java:58-59`).

The odd-looking `new TypeReference<List<SearxngResult>>() {}` exists to solve a real Java
limitation. At runtime Java erases the detail inside angle brackets — it knows you want a
`List`, but not a list *of what*. `TypeReference` is a trick that captures that detail and
carries it to Jackson, so it builds a list of `SearxngResult` records rather than a list
of anonymous maps.

`SearxngResult` (`search/SearxngResult.java:3`) is tiny — three fields: `url`, `title`,
`content`.

Then `cacheHits++` (`SearchService.java:60`) bumps the counter. Notice what did **not**
happen: no network call to the search engine, and — crucially — no credit charged. This
branch simply does not contain the two lines that spend money. Second identical search
costs nothing. That is the headline promise of this whole design.

#### Step 2d — MISS branch

A "cache miss" means the notepad was blank. The `else` block runs
(`SearchService.java:61-66`). Four things happen in this order, and the order matters.

First the librarian goes out to the newsstand. `fetchResults(query)`
(`SearchService.java:41-46`) makes the only outbound internet call in this entire flow:

```java
return searxngRestClient.get()
        .uri("/search?q={query}&format=json", query)
        .retrieve()
        .body(SearxngSearchResponse.class);
```

`RestClient` is Spring's modern object for *making* HTTP calls out to other services
(the controller *receives* calls; `RestClient` *sends* them). Reading the chain left to
right: `.get()` means use the GET verb; `.uri(...)` sets the address; `.retrieve()`
actually performs the call; `.body(SearxngSearchResponse.class)` says "and please have
Jackson turn the response text into this type".

The `{query}` in the address is a placeholder, and this is a safety feature, not just
convenience. `RestClient` substitutes the value in *and percent-encodes it*, so a
question containing a space or an `&` cannot break the address into pieces and change
what is being asked.

The base address — the `http://localhost:8080` part — is not in the Java file. It is
injected at startup from configuration (`application.yml:13-14`) into a small factory
class that builds the client object once (`config/SearxngClientConfig.java:12-17`). Port
8080 is where docker-compose publishes the SearXNG container
(`docker-compose.yml:56-57`).

SearXNG is a self-hosted search engine that queries other search engines and merges the
results. In this guide it is the newsstand outside the library. `&format=json` is what
makes it answer in JSON rather than in a web page — and that only works because the
container's settings file explicitly lists `json` as an allowed output format
(`searxng/settings.yml:9-12`).

What comes back is parsed into `SearxngSearchResponse`
(`search/SearxngSearchResponse.java:5`), a record with exactly one field: `results`, a
list of those three-field `SearxngResult` records. Then `.results()`
(`SearchService.java:62`) unwraps that single field, leaving a plain list.

> ### 🔁 HAND-OFF #3 — our service leaves the building
>
> **From:** `SearchService.fetchResults` — `SearchService.java:41-46`
> **To:** the SearXNG container, a completely separate program we did not write
> **What physically crosses:** one HTTP GET request over the network, carrying the query
> text in the address. What returns is JSON text.
>
> This crosses two boundaries at once. A **process** boundary, like hand-off #2. And a
> **trust** boundary: this is somebody else's software, and its reply is only as
> well-formed as it chooses to be.
>
> **What the other side knows about us: nothing.** SearXNG has never heard of tiers, of
> credits, or of our notepad. It returns a ranked pile of links. Every opinion we hold
> about those links — which are trustworthy, how many we want — is added afterwards, by
> us, on our side of this line.
>
> **What we do not control:** SearXNG ignores any "only give me N results" notion
> entirely. Trimming the list is strictly our job, downstream. That was confirmed against
> the running container and written down as a change to the original plan
> (`docs/PLAN.md:46`).
>
> This hand-off is also the reason this whole service exists. The project rule is that
> `retrieval-service` is the only thing allowed to touch the open web, so that all
> spending and all caching happen in exactly one place.

And then the librarian files the clipping immediately (`SearchService.java:63`):

```java
stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(queryResults), Duration.ofHours(24));
```

`writeValueAsString` is Jackson running the other way: Java objects back into JSON text,
because Redis stores text. (Turning an object into storable text like this is what
programmers call "serializing" it.) `Duration.ofHours(24)` sets a TTL — short for "time
to live", meaning how long the entry is allowed to stay before Redis throws it away.
Redis will delete this entry by itself after 24 hours, so nobody has to remember to
clean up.

Note carefully **what** gets stored: `queryResults`, the raw list straight from SearXNG.
Not graded, not filtered, not trimmed. That is exactly why `maxResults` and `minTier`
must stay out of the key, as explained in step 2a — they are applied after this line, so
they cannot possibly change what is stored here.

And then the librarian charges for it (`SearchService.java:64-65`):

```java
creditsSpent++;
quotaService.recordSpend();
```

The first line bumps a counter that will be reported back in this one response. The
second line tells the meter reader to bump the shared daily counter that survives across
requests — one Redis increment plus a refreshed 24-hour expiry
(`quota/QuotaService.java:21-25`). That story is told in full in
[02-quota-flow.md](02-quota-flow.md).

The hit branch (step 2c) contains neither line. Hits are free by design, not by accident.

And then both branches meet again. `rawResults.addAll(queryResults)`
(`SearchService.java:67`) sits outside the `if`/`else`, so it runs either way. Whatever
we got — from the notepad or from the newsstand — is added to the growing pile, and the
loop moves to the next query.

#### Step 3: grading and trimming

The loop is done. `rawResults` now holds every result from every query, ungraded and
unsorted. Four lines turn that pile into the answer (`SearchService.java:69-73`):

```java
List<SearchResult> results = rawResults.stream()
        .map(r -> new SearchResult(r.url(), r.title(), r.content(), sourceTierResolver.resolveTier(r.url())))
        .filter(r -> r.tier() <= request.minTier())
        .limit(request.maxResults())
        .toList();
```

This is a **Java Stream**, and it is worth walking one step at a time because it packs a
lot into four lines. A stream is a conveyor belt. Items ride along it, each stage does one
thing to them, and nothing actually moves until the final stage asks for the output.

The `r -> ...` bits are **lambdas** — throwaway mini-functions. `r` is just the name given
to whichever item is currently on the belt.

**`.stream()`** (`SearchService.java:69`) — put every item in `rawResults` onto the belt.

**`.map(...)`** (`SearchService.java:70`) — *transform* each item into something else. One
in, one out, same count. Here each incoming `SearxngResult` (three fields: `url`, `title`,
`content`) becomes an outgoing `SearchResult` (`dto/SearchResult.java:3`, four fields:
`url`, `title`, `snippet`, `tier`). Two things happen inside this transformation:

- The field `content` is copied into a field called `snippet`. That is a deliberate
  rename. `content` is the search engine's word; `snippet` is *our* public word. Our
  application programming interface — API, the shape we promise to callers — should not
  inherit somebody else's vocabulary.
- `sourceTierResolver.resolveTier(r.url())` is called to fill in the fourth field. That is
  the quality grader, described just below.

**`.filter(...)`** (`SearchService.java:71`) — *keep or discard*. The lambda returns
true or false; false means the item falls off the belt. Here: `r.tier() <= request.minTier()`.

That comparison direction confuses everyone the first time, so slowly: tier **1 is the
best** (official government sources), tier 4 is the worst (random blogs). Lower number
means better. So "keep everything whose tier number is at most `minTier`" reads as "keep
everything at least this good". With `minTier: 2` you keep tiers 1 and 2 and drop 3 and 4.
The field name says *min*, the arithmetic says *maximum allowed number*. The name is
unfortunate; the behaviour is "quality floor".

**`.limit(n)`** (`SearchService.java:72`) — stop after `n` items have made it through.
This is the client-side cap that exists only because the search engine offers none.

**`.toList()`** (`SearchService.java:73`) — run the belt and collect the output into a real
list.

A worked example. Suppose six results survive from the newsstand, in this order, and the
request said `minTier: 2, maxResults: 3`:

| # | Site | After `.map` — tier stamped | After `.filter` tier ≤ 2 | After `.limit(3)` |
|---|---|---|---|---|
| 1 | rbi.org.in | tier 1 | kept | kept |
| 2 | someblog.blogspot.in | tier 4 | dropped | — |
| 3 | thehindu.com | tier 2 | kept | kept |
| 4 | randomsite.example | tier 3 | dropped | — |
| 5 | ndtv.com | tier 2 | kept | kept |
| 6 | pib.gov.in | tier 1 | kept | dropped, limit reached |

Result: three items, all tier 1 or 2. Notice that a genuinely good tier-1 source got cut
purely because it arrived sixth. That is the cost of ordering `limit` last, and it is
still the correct order — see [section 6](#6-if-i-changed-x-what-breaks) for what happens
if you swap the two.

Finally, everything is boxed up (`SearchService.java:74`):

```java
return new SearchResponse(results, creditsSpent, cacheHits);
```

`SearchResponse` (`dto/SearchResponse.java:5`) is a record with exactly those three
fields.

> ### 🔁 HAND-OFF #4 — the business logic hands the answer back to the web edge
>
> **From:** `SearchService` — `SearchService.java:74`
> **To:** `RetrievalController` — `RetrievalController.java:23`, and then out to the
> network
> **What physically crosses:** one `SearchResponse` object, back up the same thread,
> the same way it came down. Still an ordinary method return.
>
> **What each side does with it:**
>
> 1. The controller does *nothing at all* to it. It returns it (`RetrievalController.java:23`).
> 2. Because the class is a `@RestController` (`RetrievalController.java:10`), Spring
>    treats that returned object as the response body and hands it to Jackson.
> 3. Jackson turns it into JSON. Record field names become JSON key names, exactly:
>    `results`, `creditsSpent`, `cacheHits`. This is why renaming a record field is a
>    visible change to your public API, not an internal refactor.
> 4. Tomcat writes the resulting bytes to the network connection, closes off the request,
>    and returns its worker thread to the idle pool, ready for the next caller.
>
> The librarian never learns whether the human was happy. Once the object is returned,
> the story is over on his side.

### The grader, since we passed through him

`SourceTierResolver.resolveTier()` — `tier/SourceTierResolver.java:17-49`. This is the
quality grader: hand him a link, he stamps it 1, 2, 3 or 4.

He works down a checklist, and returns the moment something matches.

**First, find the site name.** `URI.create(url).getHost()` (`tier/SourceTierResolver.java:19`)
pulls the host out of the address — the `www.thehindu.com` part of
`https://www.thehindu.com/news/story`. URL stands for Uniform Resource Locator, the full
web address; the "host" is just the site portion of it. If the address is malformed and
there is no host, he gives up immediately and stamps tier 3
(`tier/SourceTierResolver.java:20-22`).

**Second, tidy the site name.** Strip a leading `www.` and lowercase the rest
(`tier/SourceTierResolver.java:25-28`), so `WWW.TheHindu.com` and `thehindu.com` both
match the same entry on the list. (Small detail: this lowercasing uses the machine's
default language settings, `tier/SourceTierResolver.java:28`, whereas the query
normalizer pins its own to a fixed locale, `SearchService.java:85`. Inconsistent, though
harmless for the domain names currently on the list.)

**Third, check the honour rolls, exactly.** Is the host in the tier-1 list
(`tier/SourceTierResolver.java:31`)? Then in the tier-2 list
(`tier/SourceTierResolver.java:34`)? These are exact string matches — no wildcards, no
partial matching.

**Fourth, check the blocklist patterns.** Loop the tier-4 patterns
(`tier/SourceTierResolver.java:39-45`). These *are* wildcards, written as globs like
`*.blogspot.*`. Java has no built-in glob matcher for plain strings, so a five-line helper
converts a glob into a regular expression — a pattern language for text
(`tier/SourceTierResolver.java:53-56`). It escapes `.` so it means a literal dot, and
widens `*` into `.*` which means "any run of characters". That is how `*.blogspot.*`
catches `something.blogspot.in`.

**Otherwise, tier 3** (`tier/SourceTierResolver.java:46`) — the default for "we have no
opinion about this site".

Where do the lists come from? Not from Java. They are written in YAML — a plain-text
settings format, whose name is the joke acronym "YAML Ain't Markup Language" — in
`application.yml:1-12`. At startup Spring reads that section and fills a record,
`SourceTierProperties` (`config/SourceTierProperties.java:6-7`), and hands the record to
the grader through his constructor (`tier/SourceTierResolver.java:13-16`). The `@ConfigurationProperties`
label on the record says which YAML section to read; a single
`@ConfigurationPropertiesScan` on the application class
(`RetrievalServiceApplication.java:8`) is what makes Spring go looking for such records at
all.

The practical upshot: adding a trusted news site is a one-line edit to a text file and a
restart. No Java is touched.

## 4. Framework magic roundup (who calls what, unseen)

"Framework magic" means work that happens without any line of our code asking for it.
Here is every piece of it in this flow, made visible.

| The invisible thing | Who actually does it | When it happens |
|---|---|---|
| Calling `RetrievalController.search()` | A Tomcat worker thread — one borrowed member of a standing team of workers | once per incoming HTTP request |
| Creating `SearchService` and filling its five dependencies | Spring's container, the object factory that builds and holds your objects. It finds the class because `@Service` (`SearchService.java:23`) marks it as one to build | at startup, once |
| Picking between two possible `RestClient` objects | You did, not Spring. `@Qualifier("searxngRestClient")` names the one you want (`SearchService.java:33`). There are two candidates — one for SearXNG (`config/SearxngClientConfig.java:12-17`) and one for the extractor sidecar (`config/ExtractClientConfig.java:12-17`) — so without the name Spring cannot choose and refuses to start | at startup |
| Turning the `source-tiers:` and `quota:` YAML sections into Java records | Spring Boot's settings-binding machinery, switched on by `@ConfigurationPropertiesScan` (`RetrievalServiceApplication.java:8`) | at startup |
| Converting JSON text to and from Java objects | Jackson. Spring calls it for you on the request and the response; our own code calls it explicitly for the two Redis payloads (`SearchService.java:58,63`) | on every call |
| Keeping the Redis network connection alive | The Lettuce client library, arriving with `spring-boot-starter-data-redis` (`retrieval-service/pom.xml:20-23`) | opened on first use, then reused |
| Letting Jackson's methods compile without `try`/`catch` | This project uses Jackson 3, imported as `tools.jackson.*` (`SearchService.java:12-13`), where parse and write failures are unchecked exceptions — the compiler does not force you to handle them | at compile time |

## 5. Unhappy paths: when it breaks, where, and what state remains

Start with the blunt fact: **there is no error handling in this flow at all.** Neither
`SearchService.search()` (`SearchService.java:48-75`) nor the controller
(`RetrievalController.java:21-34`) contains a single `try`/`catch` block — verified by
searching both files for the word `try`. So any failure anywhere flies straight up and out
through Spring's generic fallback handler, and the caller sees **HTTP 500**, the status
code meaning "the server broke and is not telling you why".

That is acceptable for a learning project at this stage. It is worth knowing on purpose
rather than by surprise.

1. **The SearXNG container is down**, and this query is not on the notepad. The outbound
   call fails to connect, Spring wraps the failure in an exception, and it propagates out
   as a 500. *State afterwards:* nothing written to the notepad, and no credit spent —
   because the fetch is line 62 and the credit lines are 64 and 65
   (`SearchService.java:62-65`), so failing at 62 means 64 and 65 never run. Retrying is
   safe: the key will simply miss again until an attempt succeeds.
   COULDN'T TRACE the exact exception class — it is defined inside Spring, not in this
   repo.
2. **SearXNG is up but misconfigured.** If someone removes `json` from the allowed
   formats (`searxng/settings.yml:9-12`), SearXNG answers with a web page instead of JSON.
   Jackson cannot turn a web page into `SearxngSearchResponse`, so it throws, and you get
   a 500 with a confusing message. This exact trap has already cost this project a session
   once — it is recorded in `CLAUDE.md` under "Traps that have already cost time".
3. **Redis is down.** The very first notepad read (`SearchService.java:54`) throws a
   connection error and everything stops — even though the search engine is sitting right
   there, perfectly able to answer. There is no "cache unavailable, carry on without it"
   fallback. Redis is on the critical path here, not an optional speed-up. *State
   afterwards:* the credit counter is unreachable too, since it lives in the same Redis
   (`quota/QuotaService.java:14`).
4. **Corrupt text sitting in Redis**, for example because someone edited a value by hand.
   `readValue` (`SearchService.java:58-59`) throws, and it will throw again for every
   single request using that key until the 24-hour expiry removes it
   (`SearchService.java:63`). Nothing heals it in the meantime.
5. **A missing or nonsense `maxResults`.** Two distinct cases, and they behave
   differently:
   - **Negative**, e.g. `-1`: `.limit()` (`SearchService.java:72`) rejects a negative
     count and throws `IllegalArgumentException` → 500. Note *when*: after the search was
     already performed, the notepad already written, and the credit already spent
     (`SearchService.java:62-65`). The expensive work happens before any validation,
     because there is no validation.
   - **Zero, or simply left out of the JSON**: no error at all. `maxResults` is a plain
     `int` (`dto/SearchRequest.java:6`), so an absent value stays at Java's default of 0,
     and `.limit(0)` legitimately returns an empty list. You get
     `"results": []` back, with `creditsSpent` showing you paid for it. The same trap
     applies to `minTier`: left out, it is 0, and since every tier is 1–4 the filter
     (`SearchService.java:71`) discards everything.
6. **The same query listed twice in one request.** Not an error, and rather pleasing. The
   first pass misses and writes the notepad; the second pass reads what the first just
   wrote. The response honestly reports `creditsSpent: 1, cacheHits: 1`. The cache
   deduplicates within a single request for free, with no extra code.
7. **The daily credit allowance is exhausted.** Nothing happens. Searching continues
   normally. `remaining()` (`quota/QuotaService.java:27-31`) floors its answer at 0, and
   nothing anywhere calls it before spending. The meter reads empty and the pump keeps
   pumping.

> **Suspicious:** point 7 is the known gap, also recorded in
> [00-the-world.md](00-the-world.md#the-cast) and
> [02-quota-flow.md](02-quota-flow.md#5-unhappy-paths). `SearchService.search()` never
> checks the quota before spending it — there is no `if remaining() <= 0` anywhere in
> `SearchService.java:48-75`. Today the quota is a fuel gauge, not a fuel cut-off.

### The stampede: what two threads do to one missing key

This is the problem promised back in [step 0](#step-0-who-is-even-listening-framework-magic),
and it is not an error at all — nothing throws, nothing logs, no test fails. It is simply
waste.

Recall the setup: each request runs on its own worker thread, and several requests can be
in flight at once.

Now picture six callers asking the *same* question at the *same* moment, with nothing on
the notepad yet. Trace the six threads through `SearchService.java:54-65`:

1. All six compute the same key (`SearchService.java:53`) — the key is deliberately stable,
   so of course they match.
2. All six read the notepad (`SearchService.java:54`). All six find nothing, because none
   of them has written anything yet.
3. All six therefore take the miss branch. All six call out to the search engine
   (`SearchService.java:62`).
4. All six write the same value to the same key (`SearchService.java:63`) — harmless, just
   redundant.
5. All six spend a credit (`SearchService.java:64-65`).

One question, six identical trips outside, six credits burned. The cache did nothing,
because the cache only helps *after* somebody finishes.

The everyday analogy: six people in an office all notice the coffee pot is empty, and all
six walk to the shop to buy coffee. Nobody was wrong. Nobody checked whether someone else
had already left.

The name for this is a **cache stampede**, also called a **thundering herd**.

The standard fix is a **single-flight lock**: the first thread to notice the gap claims a
short-lived marker in Redis and is the only one allowed to fetch. The others see the
marker, wait a moment, and re-read the notepad, by which time the winner has filled it in.
Redis has an atomic "set this key only if it does not already exist" operation, which is
exactly the tool for claiming such a marker — atomic meaning it cannot be interrupted
halfway, so exactly one thread can win.

**None of that is built.** It is planned, by hand, deliberately, as a later exercise
(`docs/PLAN.md:92-95`). Today the code has no lock of any kind. Worth knowing before you
point twenty simultaneous requests at this endpoint.

### Who cleans up after all this?

Nobody, and that is the design. Notepad entries carry their own 24-hour expiry
(`SearchService.java:63`), the daily credit counter carries a 24-hour expiry
(`quota/QuotaService.java:24`), and failed operations wrote nothing to clean up in the
first place. There is no cleanup job, no cron task, no sweeper. The whole recovery
strategy is: *everything expires on its own*.

## 6. If I changed X, what breaks?

1. **Change the `"search:v1:searxng:"` prefix** (`SearchService.java:80`). Every entry
   already on the notepad becomes unreachable — still occupying memory, but nothing will
   ever look them up again, until their 24-hour expiry quietly removes them. The next
   day's searches all pay full price again. This is not a bug; it is *why* the version
   number is there. Bumping `v1` to `v2` is the intended, cheap way to invalidate a whole
   generation of entries without hunting down individual keys.
2. **Move `.limit(maxResults)` above the tier filter** (`SearchService.java:71-72`). Now
   you trim first and judge second. A page full of low-quality blogs could fill the entire
   quota of slots and then all be thrown out, leaving you with far fewer results than
   asked for — possibly zero — while excellent sources sat just past the cut. The order of
   these two stream stages is *meaning*, not formatting.
3. **Remove `@Qualifier("searxngRestClient")`** (`SearchService.java:33`). The application
   refuses to start. There are two `RestClient` objects available — one built at
   `config/SearxngClientConfig.java:12-17` and one at
   `config/ExtractClientConfig.java:12-17` — and Spring cannot guess which one this
   constructor wants. This is not hypothetical: it fired for real the day the second
   client was added, and it is recorded in `CLAUDE.md`'s Session 10 notes.
4. **Store the graded, filtered list instead of the raw one** (`SearchService.java:63`).
   Then `maxResults` and `minTier` genuinely *would* affect what is stored, so both would
   have to be added back into the key (`SearchService.java:79`) for correctness. And the
   moment they are in the key, every different combination of knobs becomes a separate
   notepad entry and a separate credit — asking the same question with `maxResults: 5` and
   then `maxResults: 10` would cost two credits for identical upstream data. Avoiding
   exactly that regression is why the recorded deviation from the original plan exists
   (`docs/PLAN.md:42-51`).
5. **Set the notepad expiry to something enormous instead of 24 hours**
   (`SearchService.java:63`). Credit spending collapses, and so does freshness — an
   article published this morning will never appear in results for a question you asked
   yesterday. There is no way to force a refresh: no code path anywhere deletes a key or
   bypasses the read at `SearchService.java:54`. Redis would also start evicting entries
   itself once it hits its memory ceiling, since the container is configured to throw out
   the least recently used entries when full (`docker-compose.yml:22`) — meaning your long
   expiry would be silently overruled anyway, in an order you do not control.

---

*Next: [02-quota-flow.md](02-quota-flow.md) — the meter reader on his own, and why the
"daily reset at midnight" is an illusion.*
