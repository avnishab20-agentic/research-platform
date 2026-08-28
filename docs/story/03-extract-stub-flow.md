# The Story of This Codebase — 03: The Extract Stub Flow

*Flow 4 of the inventory — the shortest story in the book. A request arrives, walks up
to the desk, and is handed an empty envelope. On purpose.*

**On this page:** [Plain English](#in-plain-english-30-seconds) · [1. Trigger](#1-what-triggers-this) · [2. Diagram](#2-the-journey-as-a-diagram) · [3. Narration](#3-the-narration) · [4. Framework magic](#4-framework-magic-roundup) · [5. Unhappy paths](#5-unhappy-paths) · [6. If I changed X](#6-if-i-changed-x-what-breaks)

---

## In plain English (30 seconds)

This chapter is about a door with nothing behind it yet.

You ask the service: "here are some web page addresses, please pull the article text out
of them." The service answers instantly with an empty box. It does not even look at the
addresses you sent. That is not a bug — it was written that way deliberately.

Behind that door, several helper parts are already built, tested, and sitting idle. They
have never once run for a real request.

By the end of this page you will know three things. What a **stub** is. Why a sensible
programmer would ship a door that leads nowhere. And what people mean when they say code
is "wired but never called".

---

## 1. What triggers this

Someone sends a message over the web to this address:

`http://localhost:8081/api/v1/extract`

A few words on that address, since every part of it matters. `localhost` means "this same
computer" — you are talking to a program running beside you, not out on the internet.
Next comes `8081`, the **port**. Think of one building with many numbered doors; port
`8081` is the door `retrieval-service` listens at. Last is the path, `/api/v1/extract`.
**API** stands for Application Programming Interface — a set of doors built for other
*programs* to knock on, rather than for humans clicking buttons.

The message is a **POST**. Web requests come in a few flavours, and POST is the flavour
that carries a package of data with it. (A GET, by contrast, carries no package — see
[02 — The Quota Flow](02-quota-flow.md) for one of those.) The whole conversation happens
over **HTTP**, HyperText Transfer Protocol, the plain-text rulebook browsers and servers
use to talk.

The package inside the POST is written in **JSON** — JavaScript Object Notation. JSON is
just text, arranged with curly braces and quotes, so two programs can agree on what each
value means. Here it must be shaped like this:

```json
{ "urls": ["https://thehindu.com/news/rbi", "https://pib.gov.in/press-release"] }
```

That shape is not a coincidence. It is dictated by one line of Java
(`dto/ExtractRequest.java:5`):

```java
public record ExtractRequest(List<String> urls) {
}
```

A **record** is a short Java feature for "a small object that only holds values". You name
the fields in the brackets and Java writes the boring parts for you — the constructor, the
getters, `equals`, `toString`. This record holds exactly one field: `urls`, a list of text
strings. So the JSON must contain a key literally spelled `urls` holding a list of text.

**URL** stands for Uniform Resource Locator — the technical name for a web address.

**What the endpoint is *supposed* to do**, per the project plan (`docs/PLAN.md:32`): for
each URL, go and get the page, throw away the menus and adverts, and return the clean
article text, plus a quality score for the source and a verdict about how it went.

That answer would come back as a list of `Document` records
(`dto/Document.java:3`):

```java
public record Document(String url, String text, int tier, String status) {
}
```

Each of those four fields tells a caller one thing. `url` is which page this is. `text` is
the cleaned-up article. `tier` is a quality grade from 1 to 4 — a government press
release is tier 1, a random blog is tier 4, and the grading rules live in a settings file
(`retrieval-service/src/main/resources/application.yml:1-12`) and are applied by
`SourceTierResolver`, the quality grader (`tier/SourceTierResolver.java:17-49`). `status`
is how the attempt went; the plan lists five possible words: `OK`, `PAYWALLED`,
`ROBOTS_DENIED`, `UNREACHABLE`, `TOO_LARGE` (`docs/PLAN.md:64`).

The list of documents gets wrapped in one outer record before it is sent back
(`dto/ExtractResponse.java:6`):

```java
public record ExtractResponse(List<Document>documents) {
}
```

That is the plan. Now here is what actually happens.

## 2. The journey as a diagram

Read this top to bottom, like a comic strip. Each arrow is one hand-off.

```text
Caller - a browser, curl, or another program
    │  POST /api/v1/extract carrying a list of URLs
    ▼
Tomcat worker thread - the built-in web server
    │
    │  note: Jackson turns the JSON text into an
    │  ExtractRequest object
    ▼
RetrievalController - the doorman
    │  extract(request)
    │
    │  replies: an ExtractResponse holding an empty list
    ▼
Tomcat worker thread - the built-in web server
    │  replies: {"documents":[]}
    ▼
Caller - a browser, curl, or another program
```

Two names in that picture need unpacking.

**Tomcat** is the web server built into the application. It is the switchboard that
listens on port `8081`, accepts the raw bytes of the incoming request, and decides which
Java method should handle them. A **thread** is one worker inside the program that can do
one job at a time; Tomcat keeps a pool of them so several callers can be served at once.

**Jackson** is the library that translates between JSON text and Java objects, in both
directions. Turning JSON text into an object is called **deserialization**; going the
other way — turning an object into JSON text — is **serialization**. Nobody in our code
calls Jackson by name here — the framework does it for us, which is exactly what
section 4 is about.

That is the entire waterfall. It only has three participants, because the fourth one —
whoever would actually fetch and clean the pages — never appears.

## 3. The narration

Here is the whole implementation. All four lines of it
(`web/RetrievalController.java:26-29`):

```java
@PostMapping("/extract")
public ExtractResponse extract(@RequestBody ExtractRequest request){
    return new ExtractResponse(List.of());
}
```

Let us walk the words in order.

`@PostMapping("/extract")` is an **annotation** — a label starting with `@` that you
stick on Java code to tell a framework something about it. This one tells Spring: "when
a POST arrives at `/extract`, call this method." **Spring** is the framework this
service is built on; it does an enormous amount of setup work so the programmer does
not have to. The `/api/v1` part of the path comes from a second annotation sitting on
the class itself (`web/RetrievalController.java:11`), and the two get glued together
into `/api/v1/extract`.

And then `@RequestBody ExtractRequest request` tells Spring: "take the JSON package
that came with this POST, hand it to Jackson, and give me back a filled-in
`ExtractRequest` object."

And then comes the punchline: `return new ExtractResponse(List.of());`.

`List.of()` builds an empty, unchangeable list. So Jackson dutifully parses the
caller's URLs into `request`, and then the method never touches `request` again. The
URLs are dropped on the floor. There is no fetching, no cache lookup, no quality
grading, no call to the Python helper service. The caller gets HTTP status **200**,
meaning "fine, here you go", along with `{"documents":[]}`, instantly.

A method like this — correct shape, no real work inside — is called a **stub**.

### Why anyone would ship a door that leads nowhere

This is a deliberate habit, and it has a name: **contract-first**.

The "contract" is the promise the endpoint makes to whoever calls it. What address to
knock on. What JSON to send. What JSON comes back. Everything a caller needs to know, and
nothing about how the work gets done.

Building the stub first lets you nail down that promise cheaply. Other people can start
writing code that calls you today. You can confirm the address routes correctly, that your
JSON parses, that your reply parses on their end. All of that is settled before anyone
writes the hard part.

Later, the real body grows underneath, and the contract never moves. Callers do not need to
change a single line — the same request suddenly returns real documents instead of an
empty list.

That is exactly what happened here. Session 4 of this project built all three of
`retrieval-service`'s endpoints as stubs at once. Two of them have since grown real bodies:
`/api/v1/search` now calls out to a search engine and caches the answer
(`web/RetrievalController.java:21-24`, narrated in
[01 — The Search Flow](01-search-flow.md)), and `/api/v1/quota` now reads a real counter
(`web/RetrievalController.java:31-34`, narrated in
[02 — The Quota Flow](02-quota-flow.md)). This one has not. The project's own log,
`CLAUDE.md`, carries it as an open item through Sessions 6, 7, 8, 9 and 10 — five sessions
of "still a stub".

So the stub is honest scaffolding around a known hole. Everybody involved knows the hole
is there.

### What "wired but never called" means

Several parts of the missing implementation already exist. They compile. They have tests.
They pass. And no running request ever reaches them.

That is the phrase "wired but never called". Some of these objects are genuinely *built*
when the program starts — they sit in memory, ready — but nothing ever calls their
methods. Others are plain utility classes that only the tests ever touch.

Here is the current wiring, with dashes showing the connection that does not exist:

```text
RetrievalController.extract - the doorman
    │
    ▼
returns an empty list and stops

──────────────────────────────────────────────────
 the missing wire (dashed): nothing actually runs
 these parts for a real request
──────────────────────────────────────────────────

Built and tested - but no running request reaches them:

ExtractCacheKey - the label maker
    ├── uses: UrlNormalizer - the alias detective
    └── uses: Hashing - the fingerprint clerk
              (already employed by the search flow)

ExtractorClient - the courier - unemployed
```

Now meet each idle part.

#### The courier: `ExtractorClient`

`extract/ExtractorClient.java:21-27`:

```java
public ExtractorResult extract(String url, String html) {
    return extractorRestClient.post()
            .uri("/extract")
            .body(new ExtractorRequest(url, html))
            .retrieve()
            .body(ExtractorResult.class);
}
```

This method's whole job is to carry a page's raw HTML across to a small Python service —
a separate program running beside us whose only skill is stripping menus and adverts off a
web page. That Python program is the **sidecar**: a helper container that lives next to the
main service like a motorcycle sidecar. Its story is
[04 — The Extractor Sidecar Flow](04-sidecar-extraction-flow.md).

The courier is fully equipped for the trip. Spring even hires him at start-up: the
`@Component` label on the class (`extract/ExtractorClient.java:11`) tells Spring "build one
of these when the program boots and keep it". An object that Spring builds and keeps for
you is called a **bean**. A second bean, the pre-configured HTTP caller he uses, is built
by `config/ExtractClientConfig.java:12-17`, pointed at
`http://localhost:8000` by a settings line (`application.yml:19-20`).

So the courier exists in memory every time the service runs. He has a van, an address, and
a delivery route. Nobody ever hands him a parcel.

#### The label maker: `ExtractCacheKey`

`extract/ExtractCacheKey.java:13-15`:

```java
public static String of(String url) {
    return PREFIX + Hashing.sha256Hex(UrlNormalizer.normalize(url)).substring(0, 16);
}
```

`PREFIX` is the text `"extract:v1:"` (`extract/ExtractCacheKey.java:8`).

To follow this you need one idea: a **cache**. A cache is a small notepad where you write
down an answer you already worked out, so next time you can read it instead of doing the
work again. This project's notepad is **Redis**, a separate program that stores simple
key-and-value pairs in memory and answers in well under a millisecond.

Every note on that pad needs a label, so you can find it again. That label is the **cache
key**. `ExtractCacheKey.of()` is the machine that mints those labels, and it does its job
in three steps.

**Step one — the alias detective, `UrlNormalizer`** (`url/UrlNormalizer.java:16-34`). The
same article can be written down in many different ways. Compare these two:

```
https://www.TheHindu.com/news/rbi/?utm_source=twitter#top
https://thehindu.com/news/rbi
```

Same article. Four cosmetic differences. The detective strips every one of them, one
rule at a time. `HTTPS` is lower-cased to `https` (`UrlNormalizer.java:19`). The host
`www.TheHindu.com` is lower-cased and loses its `www.` prefix
(`UrlNormalizer.java:21-24`). A trailing slash on the path is chopped off
(`UrlNormalizer.java:26-29`). Tracking rubbish in the query — anything starting `utm_`,
plus `fbclid`, `gclid` and `ref` — is filtered out, and whatever genuinely matters is
kept and sorted into alphabetical order (`UrlNormalizer.java:36-45`, with the noise list
at `UrlNormalizer.java:10-11`). And the `#top` fragment disappears for free: a fragment
only tells a browser where to scroll, and the code simply never reads it, so it never
makes it into the rebuilt address (`UrlNormalizer.java:33`).

Both spellings above come out as the same clean text: `https://thehindu.com/news/rbi`.
The eight tests in `url/UrlNormalizerTest.java:9-63` pin each of those rules down
individually, and `UrlNormalizerTest.java:58-63` throws all of them at it at once.

One rule the detective deliberately does *not* apply: it never strips a query parameter
just because it looks unimportant. `?page=1` and `?page=2` are genuinely different pages,
so they stay different (`UrlNormalizerTest.java:37-42`).

**Step two — the fingerprint clerk, `Hashing`** (`hash/Hashing.java:13-21`). The clean
address is fed through **SHA-256**, which stands for Secure Hash Algorithm, 256-bit. A hash
is a one-way machine: put any text in, get a fixed-length scramble out. The same input
always produces the same scramble, and a different input almost certainly produces a
different one. You cannot run it backwards to recover the original text.

Why bother? Purely for tidiness. Web addresses are long, contain colons and slashes and
spaces, and vary wildly in length. A hash gives you a short, uniform, punctuation-free
label instead. Note carefully: hashing does **not** make the key *correct*. Correctness came
in step one, from the detective. Hashing only makes the key *neat*.

Only the first 16 characters of the scramble are kept
(`extract/ExtractCacheKey.java:14`) — plenty to keep two different pages apart, and shorter
to store.

**Step three — glue on the prefix.** The finished label for our example article is:

```
extract:v1:a9903c2efc5a894f
```

The `v1` is a version stamp, and it is a small piece of cleverness. If the extraction logic
ever changes so that the old cached text is no longer trustworthy, you do not have to hunt
down and delete thousands of notes. You bump `v1` to `v2`, and every future label misses
the old notes entirely. They then expire on their own.

Three tests guard this machine (`extract/ExtractCacheKeyTest.java:9-31`). The important one
is `equivalentUrlsShareOneKey` (`ExtractCacheKeyTest.java:18-23`): it asserts that the two
spellings above produce *the same* key. Four cosmetic differences collapse to one label,
so one article gets fetched once instead of four times. The third test
(`ExtractCacheKeyTest.java:25-30`) guards the opposite direction, insisting `?page=1` and
`?page=2` stay apart — so nobody later "optimises" the detective into stripping too much.

#### What is still missing entirely

The plan spells out the orchestration nobody has written yet (`docs/PLAN.md:53-57`).
The steps run like this. First, normalise the URL — done, the detective exists. Then
check the Redis notepad under that key first, and only do real work on a miss. This
read-first pattern is called **cache-aside**, which just means "look in the notepad
before doing the expensive work"; the search flow already does it, at
`search/SearchService.java:53-66`. Then keep each note for **7 days** before it
expires. That expiry countdown is a **TTL**, short for Time To Live — a self-destruct
timer on the note. Search results only get 24 hours (`SearchService.java:63`) because
news moves; an extracted article body is far more stable, so it can sit longer. Then
refuse any document bigger than **200KB** — kilobytes, roughly 200,000 characters
(`docs/PLAN.md:57`). Then build each `Document`, with `tier` filled in by the quality
grader (`tier/SourceTierResolver.java:17-49`). And finally decide the `UNREACHABLE`,
`ROBOTS_DENIED` and `TOO_LARGE` verdicts here, in Java, at fetch time. The Python
sidecar cannot possibly decide those, because it never touches the network itself — it
is handed HTML that somebody else downloaded. Its entire vocabulary is `OK` or
`PAYWALLED` (`extractor/main.py:36`). This split is recorded as a deliberate deviation
from the original spec at `docs/PLAN.md:66-73`.

There is also a planned safety valve (`docs/PLAN.md:97-98`): a `Semaphore(4)` in front of
the sidecar. A **semaphore** is a counter of permits — think four hooks by a door holding
four passes. A worker must take a pass before entering and hangs it back on the way out.
With only four passes, at most four extractions can be in flight at once, so a dozen
simultaneous researchers cannot flatten the little Python service.

> **COULDN'T TRACE** any runtime path from this endpoint to `ExtractorClient` — there is
> not one yet. A search across every `.java` file finds `ExtractorClient` mentioned only
> inside its own file, and `ExtractCacheKey.of()` called only from its own test.

> **Suspicious:** `Hashing` is the one part of this chain that is *not* idle — the search
> flow already calls it (`search/SearchService.java:80`). It was deliberately lifted out of
> `SearchService` into a shared class so the two cache-key recipes could not silently drift
> apart. That means the "unemployed" label applies to `ExtractorClient`, `ExtractCacheKey`
> and (through it) `UrlNormalizer`, but not to `Hashing`.

## 4. Framework magic roundup

"Framework magic" means work that plainly happens, but that you cannot find by reading
our code — because Spring does it for us. Steps 0 and 1 here are identical to flow 1's.
First, **Tomcat accepts the connection**: the web server is started automatically when
the application boots, and nobody in this repository writes socket code. Then **a route
table maps the request to a method**: at start-up Spring scans for `@RestController`
classes (`web/RetrievalController.java:10`) and reads their `@PostMapping` /
`@GetMapping` labels, building a lookup table of path to method — that is how
`POST /api/v1/extract` finds `extract()` with nobody writing an `if` statement. Then
**Jackson parses the request**, triggered purely by the `@RequestBody` label
(`web/RetrievalController.java:27`).

One extra wrinkle is worth pointing out, because it is the entire justification for
stubbing. The *response* half of the magic still runs in full. `@RestController` means
"whatever these methods return, convert it to JSON and send it as the reply body". So
Spring hands `new ExtractResponse(List.of())` to Jackson, which walks the record, finds one
field named `documents` holding an empty list, and produces the literal bytes:

```
{"documents":[]}
```

Even a stub gets the complete JSON treatment. Which is precisely why a stub proves the
contract: the caller receives a genuinely well-formed reply in the final shape, produced by
the real machinery. Only the content is missing.

Two more pieces of magic happen at start-up and then simply wait. Spring builds the
`ExtractorClient` bean because of its `@Component` label
(`extract/ExtractorClient.java:11`), and it builds the HTTP caller that bean depends on,
because of the `@Bean` method in `config/ExtractClientConfig.java:12-17`.

Both objects are alive in memory on every run of the service. Neither is ever used. That is
"wired but never called", literally.

## 5. Unhappy paths

An "unhappy path" is what happens when something goes wrong. This endpoint has an unusually
short list, for an unusual reason: it depends on nothing, so there is almost nothing to
break.

1. **Malformed JSON, or JSON of the wrong shape.** Jackson fails while trying to build
   the `ExtractRequest`, Spring turns that failure into HTTP status **400** — "Bad
   Request" — and our method is never entered at all. Worth noticing: the stub is
   already exactly as strict about input shape as the finished version will be, because
   that strictness comes from the record's definition (`dto/ExtractRequest.java:5`),
   not from the method body.

2. **An empty `urls` list, or a million URLs.** Both produce the same empty answer,
   because the method never reads `urls`. This tells you something about the future: the
   stub checks nothing beyond shape. There is no length check, no "at least one URL"
   rule, no cap on how many you may ask for — and nothing in `ExtractRequest.java:5`
   enforces any. All of that is work the real implementation must add.

3. **Everything else is unreachable.** No network call, no Redis read, no database, no
   sidecar. There is no dependency left that could fail.

State after any failure: pristine. This endpoint cannot corrupt anything, because it
touches nothing.

## 6. If I changed X, what breaks?

1. **Rename the `urls` field** in `dto/ExtractRequest.java:5` to something else, say
   `pageUrls`. Nothing fails loudly. Callers keep sending a JSON key spelled `urls`,
   Jackson finds no field by that name to fill, and the renamed field is left as `null`.
   Today nobody notices, because nobody reads it. The day the real body is written, it
   would silently process an empty list on every request — the worst kind of bug, one
   with no error message. The lesson is blunt: in a web API, **the field names in your
   record *are* the public contract**. Renaming one is a breaking change to every
   caller, even though the Java compiler will happily let you do it.

2. **Wire up the real body but forget `UrlNormalizer` in the cache key.** Then
   `https://thehindu.com/news/rbi?utm_source=twitter` and `https://thehindu.com/news/rbi`
   hash to two completely different labels, land in two separate notepad entries, and get
   fetched twice. Multiply that by every share link on social media and the cache stops
   earning its keep. This is exactly the bug class `ExtractCacheKeyTest.java:18-23` was
   written to pin down.

3. **Skip the 200KB cap** (`docs/PLAN.md:57`). One enormous page then lands whole in Redis
   and, later, whole inside an AI prompt. Worse, the notepad has a fixed size limit and a
   policy for what to do when full: `allkeys-lru`, configured at `docker-compose.yml:22`.
   **LRU** stands for Least Recently Used — when memory runs out, Redis throws away
   whichever entries were read longest ago to make room. So one giant document does not just
   waste space; it actively evicts useful neighbours that were doing their job. Caps protect
   the cache's whole ecosystem, not just its memory ceiling.

4. **Let many callers extract the same uncached URL at the same moment.** Six requests
   arrive together, all six check the notepad, all six find nothing (nobody has written the
   answer yet), and all six fetch the identical page. One page, six downloads. That pile-up
   has a name — a **thundering herd**, also called a cache stampede. The plan reserves space
   for two defences against it (`docs/PLAN.md:92-98`): a single-flight lock, where the first
   arrival claims the job and the rest wait briefly then re-read the notepad, and the
   four-permit semaphore described earlier. Neither is built yet, which is fine — nothing
   calls this endpoint yet either.
