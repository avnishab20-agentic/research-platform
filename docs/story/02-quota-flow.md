# The Story of This Codebase — 02: The Quota Flow

*Flow 2 of the inventory. Short road: one web request → one read from Redis → back.
But it carries the project's whole "don't spend the family fortune" idea, so it gets
its own chapter.*

**On this page:** [1. Trigger](#1-what-triggers-this) · [2. Diagram](#2-the-journey-as-a-diagram) · [3. Narration](#3-the-narration) · [4. Framework magic](#4-framework-magic-roundup) · [5. Unhappy paths](#5-unhappy-paths) · [6. If I changed X](#6-if-i-changed-x-what-breaks)

---

## In plain English (30 seconds)

Searching the web costs money. So the program counts how many searches it has done today.

This chapter follows one tiny question: *how many searches do I have left?*

You ask the program. The program asks a small fast memory store called Redis for
today's count. It subtracts that count from a limit of 1000, and tells you the answer.

By the end you will know what the commands `INCR` and `EXPIRE` really do, why today's
date is baked into the name of the counter, why the "reset at midnight" is a clever
illusion rather than a real alarm clock, and why nothing actually stops you when the
number reaches zero.

---

## 1. What triggers this

Someone wants to know how many search credits are left today.

A **credit** here is not real money. It is just "one search we sent out to the
internet." The project counts them so it can see how fast it is burning through them.

The trigger is a **web request**. A web request is one message sent from a program
like a browser to a program that is listening for messages. The rules for those
messages are called **HTTP**, short for *HyperText Transfer Protocol* — it is simply
the agreed format that both sides speak.

The specific request is:

```
GET http://localhost:8081/api/v1/quota
```

Reading that from left to right:

- **GET** is the *verb*. In HTTP, `GET` means "give me something, change nothing."
  The other verb you will see in this project is `POST`, which means "here is some
  data, do something with it."
- **`localhost`** means "this same computer." **`8081`** is the **port** — think of one
  computer as an apartment building and a port as a specific flat number. The
  retrieval service lives at flat 8081 because `application.properties:2` sets
  `server.port=8081`.
- **`/api/v1/quota`** is the path. It is built from two pieces of code:
  `@RequestMapping("/api/v1")` sits on the whole class (`RetrievalController.java:11`)
  and `@GetMapping("/quota")` sits on the one method (`RetrievalController.java:31`).
  Glue them together and you get `/api/v1/quota`. (Those `@`-words are **annotations**:
  labels you stick on Java code that a framework reads and acts on. More on that in
  Step 0.)

There is no data attached to this request. No body, no parameters. The only real input
is *what day it is today*, and nobody has to send that — the code asks the computer's
clock itself (`QuotaService.java:33`).

What comes back is a short piece of text in **JSON** format. JSON, short for
*JavaScript Object Notation*, is just a plain-text way of writing data as
`"name": value` pairs inside curly braces:

```json
{"creditsRemaining":963}
```

That exact field name comes from the `QuotaResponse` record
(`dto/QuotaResponse.java:3`), whose one and only field is called `creditsRemaining`.
A **record** is a short Java class whose whole job is to hold a few values — you write
one line and Java generates the constructor and getters for you.

This endpoint is one of the three promised in the plan (`docs/PLAN.md:33`:
`GET /api/v1/quota → credits remaining`). An **endpoint** is one address the service
answers on, together with the verb it accepts.

## 2. The journey as a diagram

Below is a **sequence diagram**. Read it top to bottom as time passing. Each vertical
line is one participant. A **solid arrow** is somebody asking somebody else to do
something. A **dashed arrow** is the answer coming back.

```text
You, in a browser or with curl
    │  GET /api/v1/quota, no data attached
    ▼
Tomcat worker thread
    │  call the quota method
    ▼
RetrievalController, the doorman
    │  remaining, how many are left?
    ▼
QuotaService, the meter reader
    │  read whatever is stored under the name
    │  quota:v1:2026-08-23
    ▼
Redis, the notepad on the desk
    │
    ├── a counter already exists for today
    │     ► reply: the text "37"
    │
    └── nobody has searched yet today
          ► reply: nothing at all, a null answer
    │
    │  (now the answer travels back up the chain)
    │
    ▼
QuotaService, the meter reader
    │  replies: a plain whole number, for example 963
    ▼
RetrievalController, the doorman
    │  replies: a QuotaResponse record holding that number
    ▼
Tomcat worker thread
    │  replies: JSON text, creditsRemaining 963
    ▼
You, in a browser or with curl
```

The cast, in everyday words:

| In the diagram | What it really is | Everyday picture |
|---|---|---|
| Tomcat worker thread | Tomcat is the small web server that Spring Boot starts inside the program; it listens on the port. A **thread** is one worker inside the program that can do one task at a time. | The building's front desk staff. One member of staff handles your visit start to finish. |
| RetrievalController | A Java class marked as the web entry point (`RetrievalController.java:10-12`). | The doorman. Takes your question, passes it on, hands the answer back. Does no thinking. |
| QuotaService | A Java class holding the counting logic (`QuotaService.java:12-13`). | The meter reader. Walks to the meter, reads the dial, reports the number. |
| Redis | A separate program that stores values under names, entirely in memory, so reads take well under a millisecond. Runs in its own container: `docker-compose.yml:19-24`. | The notepad on the desk. Fast to scribble on, fast to read, and it is not the filing cabinet. |

Redis stores **key → value** pairs. A **key** is just the name you file something under
(here: `quota:v1:2026-08-23`). The **value** is the thing filed (here: the text `37`).
Redis has no idea what a credit is. To Redis this is a name and some text.

## 3. The narration

### Step 0 — same invisible doorman mechanics

Nobody in this codebase writes the code that reads bytes off the network. Tomcat, the
web server bundled inside the application, does that. It came in with one line in the
build file: `spring-boot-starter-webmvc` (`retrieval-service/pom.xml:24-27`). A
**starter** is a bundle of libraries you list once and get a whole working stack from.

When your request lands, Tomcat hands it to one free worker thread. That thread has to
decide which Java method should handle `GET /api/v1/quota`. It finds the method
labelled `@GetMapping("/quota")` (`RetrievalController.java:31`).

That decision is made by matching annotations. An **annotation** is metadata: a label
starting with `@` that does nothing by itself, but which a framework looks for and acts
on. `@RestController` (`RetrievalController.java:10`) says "this class answers web
requests, and whatever my methods return should be turned into JSON." `@RequestMapping`
(`:11`) sets the shared path prefix. `@GetMapping` (`:31`) claims one verb-plus-path
combination.

A `GET` request carries no body, so nothing has to be unpacked on the way in. Compare
the neighbouring method, `search`, which declares `@RequestBody SearchRequest request`
(`RetrievalController.java:22`) — that annotation asks the library **Jackson** to turn
incoming JSON text into a Java object. The quota method declares no parameters at all
(`RetrievalController.java:32`), so that whole step simply does not happen here.

### Step 1 — the controller delegates, again one line

`RetrievalController.quota()` — `RetrievalController.java:31-34`:

```java
@GetMapping("/quota")
public QuotaResponse quota(){
    return new QuotaResponse(quotaService.remaining());
}
```

Read it inside-out. `quotaService.remaining()` asks the meter reader for a number. That
number is wrapped in a brand-new `QuotaResponse` record, whose single field is
`creditsRemaining` (`dto/QuotaResponse.java:3`). The method returns that record.

The controller never builds JSON text itself. Because the class is annotated
`@RestController` (`RetrievalController.java:10`), Spring takes the returned object and
hands it to Jackson, which writes `{"creditsRemaining":963}` back down the connection.
Field names in the JSON come straight from the record's field names.

Where did `quotaService` come from? It is a field on the class
(`RetrievalController.java:14`), filled in by the constructor
(`RetrievalController.java:16-19`). Nobody in this codebase ever writes
`new RetrievalController(...)`. Spring does it at startup — see Step 4.

> **HAND-OFF.** The controller hands over *nothing* and receives a plain `int`. It does
> not know where the number came from, that Redis exists, or what a credit is. Going
> the other way, `QuotaService` does not know that anyone is asking over the web, and
> knows nothing about HTTP. Each side could be replaced without the other noticing.

### Step 2 — the meter reader reads the meter

`QuotaService.remaining()` — `QuotaService.java:27-31`:

```java
public int remaining(){
    String used = redis.opsForValue().get(key());
    int spent = (used ==null )? 0 : Integer.parseInt(used);
    return Math.max(0, dailyLimit-spent);
}
```

Three lines, four ideas. Take them one at a time.

**First, build today's name.** `key()` returns `"quota:v1:" + LocalDate.now()`
(`QuotaService.java:32-34`). `LocalDate.now()` asks the computer for today's date in
the machine's own time zone. Today that produces the text `quota:v1:2026-08-23`.

The three parts of that name each earn their place. `quota` says what it is. `v1` is a
version marker — a label that says which version of the stored value this is. If that
meaning ever changes, you bump it to `v2` and every old entry is simply ignored and
eventually thrown away. That is easier and safer than hunting down and deleting old
entries one by one. The date is the reset mechanism, and it gets its own sub-section
below.

Small piece of history: the middle colon was missing at first, so the key read
`quota:v12026-08-23`. It worked, but it did not match the naming style every other key
in the project follows. `CLAUDE.md`'s Session 8 log flagged it as a one-character fix,
and Session 10 landed it.

**Second, ask Redis.** `redis` here is a `StringRedisTemplate`
(`QuotaService.java:14`) — a Spring object whose methods send commands to Redis and
hand you back the reply. `opsForValue()` picks the set of commands that
work on plain text values, and `.get(...)` sends the Redis command `GET`. So this line
means: *Redis, what text is stored under the name `quota:v1:2026-08-23`?*

**Third, handle "nothing is stored".** Redis answers `null` when the name is unknown.
`null` in Java means "no object here at all." That happens on a fresh day, or right
after the entry expired, or on a brand-new machine. The code treats it as zero spent
(`QuotaService.java:29`) rather than blowing up. If there *is* text, `Integer.parseInt`
turns the characters `"37"` into the number 37 — Redis stores everything as text, so
this conversion is mandatory.

**Fourth, subtract and floor.** `dailyLimit - spent` is the answer, and
`Math.max(0, ...)` (`QuotaService.java:30`) refuses to return anything below zero. So
the worst report you can ever get is `0`, never `-42`.

`dailyLimit` is not hard-coded in the Java file. It is copied in once, in the
constructor, from a settings object: `this.dailyLimit = props.dailyLimit()`
(`QuotaService.java:19`). That settings object is `QuotaProperties`
(`config/QuotaProperties.java:6-7`), which is filled from the configuration file
`application.yml:16-17`:

```yaml
quota:
  daily-limit: 1000
```

**YAML** — *YAML Ain't Markup Language* — is a plain-text settings format where
indentation shows nesting. Note the spelling difference: the file says `daily-limit`
and the Java field is `dailyLimit`. Spring matches those two automatically; it is
happy to ignore dashes and letter case when lining a setting up with a field.

#### What INCR and EXPIRE actually do

Reading the meter is only half the story. Something has to move the dial. That is
`recordSpend()` — `QuotaService.java:21-25`:

```java
public void recordSpend(){
    String key=key();
    redis.opsForValue().increment(key);
    redis.expire(key, Duration.ofHours(24));
}
```

`increment(key)` (`:23`) sends the Redis command **INCR**. `INCR` means: *look at the
number stored under this name, add one, store it back, and tell me the new value.* Its
best feature is what happens when the name does not exist yet: Redis treats the missing
value as 0 and stores 1. So you never need to write "if today's counter is missing,
create it with zero first." The very first search of the day just works.

`INCR` is also **atomic**. Atomic means the read-add-write happens as one single step
that nothing can split apart, so no other command can slip in halfway through. If six searches
land at the exact same moment, you end up at 6 — never at 1 because five workers all
read "0" before any of them wrote.

`expire(key, Duration.ofHours(24))` (`:24`) sends the Redis command **EXPIRE**. `EXPIRE`
attaches a self-destruct timer to a name: *delete this entry automatically in 86,400
seconds.* That countdown is called the **TTL**, short for *time to live*. Without it,
every day would leave one more dead counter lying in Redis's memory forever.

Notice that `EXPIRE` runs on *every* spend, not only the first. So the timer restarts
at a full 24 hours each time a search happens. On a busy day, today's counter keeps
being given a fresh 24-hour lease.

#### Why the date is inside the key, and why midnight is an illusion

There is no alarm clock in this codebase. Nothing wakes up at 00:00 to reset anything.
A search of the whole repository for scheduled jobs finds none — no `@Scheduled`
annotation exists anywhere in the Java code.

The reset works purely because the date is part of the name (`QuotaService.java:33`).
At 23:59 the code builds the name `quota:v1:2026-08-23` and finds a counter at 37. At
00:01 the same code builds `quota:v1:2026-08-24`, a name nothing has ever been stored
under. Redis returns `null`. The null branch treats that as 0 spent
(`QuotaService.java:29`), and you are reported as having the full 1000 again.

Nothing was reset. The code simply started looking at a different, empty shelf.
Yesterday's counter is still sitting in Redis with its 37 on it — it is just orphaned,
because no code will ever ask for that name again. It quietly disappears when its
24-hour timer runs out.

Which means the TTL is not the reset. **The TTL is only the cleaner.** It exists so
that old, unreachable counters do not pile up in memory. The date in the key is what
makes the day roll over.

One consequence worth holding onto: "midnight" means midnight in the time zone of the
machine running the Java program, because `LocalDate.now()` (`QuotaService.java:33`) is
asked without a time zone and falls back to the system default.

#### Reading never writes

Look again at what `remaining()` does *not* do: it never stores anything
(`QuotaService.java:27-31`). Checking the fuel gauge does not burn fuel. You can hit
`GET /api/v1/quota` a thousand times and the count will not move.

All the writing lives in `recordSpend()` (`QuotaService.java:21-25`), and there is
exactly one place in the whole codebase that calls it: `SearchService.java:65`. That
call sits inside the cache-miss branch (`SearchService.java:61-66`). A **cache** is a
small local store of previous answers; a **miss** means the answer was not there and we
had to go out to the internet for it. On a **hit** — the answer was already stored
(`SearchService.java:57-60`) — `recordSpend()` is never reached.

That is the whole point of the caching work in flow 1: the second identical search
costs zero credits, because nothing outside the program was ever contacted.

## 4. Framework magic roundup

"Framework magic" means the work nobody in this repository wrote, that happens anyway.
Here is all of it, for this one flow.

**A worker answers the phone.** Tomcat accepts the network connection, reads the raw
HTTP message and makes sense of it, picks a free thread, and matches the path to the
`@GetMapping("/quota")` method (`RetrievalController.java:31`). All of it arrived with
`spring-boot-starter-webmvc` (`retrieval-service/pom.xml:24-27`).

**Objects get built for you, once, at startup.** Nobody writes
`new RetrievalController(...)` or `new QuotaService(...)`. At startup Spring scans the
code, finds the classes it is meant to manage — `@RestController`
(`RetrievalController.java:10`) and `@Service` (`QuotaService.java:12`) — creates one
of each, and passes them to each other through their constructors
(`RetrievalController.java:16-19`, `QuotaService.java:17-20`). An object created and
held by Spring in this way is called a **bean**, and the handing-in of one bean to
another is called **dependency injection** — Spring puts the pieces each object needs
into its constructor for it. Because the dependencies arrive through the constructor,
the fields can be `final`: set once, never reassigned.

**The Redis connection object appears out of nowhere.** Nobody writes code to connect to
Redis. Listing `spring-boot-starter-data-redis` in the build file
(`retrieval-service/pom.xml:20-23`) is enough: Spring Boot notices the library on the
classpath (the list of libraries the program loads) and creates a ready-to-use
`StringRedisTemplate` bean, which is then injected into `QuotaService`
(`QuotaService.java:17-20`). This "see a library, build sensible
objects for it" behaviour is called **auto-configuration**.

**And it connects to the right place by pure default.** There is no Redis address
anywhere in `retrieval-service`'s settings — a search of its resources folder finds no
`spring.data.redis.*` entry at all. Spring Boot's built-in default is `localhost:6379`,
and the Redis container publishes exactly that port (`docker-compose.yml:23-24`), so
the two meet without anyone writing an address down.

**Text settings become a typed Java object.** `application.yml:16-17` becomes
`QuotaProperties` (`config/QuotaProperties.java:6-7`) because the record is annotated
`@ConfigurationProperties(prefix = "quota")` (`:6`) and the application class is
annotated `@ConfigurationPropertiesScan` (`RetrievalServiceApplication.java:8`), which
tells Spring to go looking for such records. A "typed" object is one whose fields have
fixed kinds — here a number — so Spring can check the setting as it reads it. The upside
over reading raw text: if someone writes `daily-limit: banana`, the application fails
loudly at startup instead of at 3 a.m.

**Objects become JSON on the way out.** The `quota()` method returns a Java record; the
caller receives text. Jackson does that conversion, triggered by `@RestController`
(`RetrievalController.java:10`).

**Nothing runs on a timer.** No background job tops anything up. As explained above,
the daily reset is an illusion produced entirely by putting the date in the key
(`QuotaService.java:33`).

## 5. Unhappy paths

1. **Redis is down.** The `.get()` call (`QuotaService.java:28`) cannot reach the
   server, so it throws a connection **exception** — Java's way of saying "something
   went wrong, I am stopping this method." Nothing here catches it, so Spring turns the
   uncaught exception into an HTTP **500** response, the status code for "the server
   itself broke." Same single point of failure as flow 1: no Redis, no answers.
2. **The stored text is not a number.** If someone hand-edits the value, or a future bug
   writes rubbish under that name, `Integer.parseInt(used)` (`QuotaService.java:29`)
   throws a `NumberFormatException` and you get a 500. It keeps failing until that
   entry expires, because nothing overwrites it with a clean value and there is no
   defensive `try/catch` around the parse.
3. **The clock ticks over to a new day mid-request.** Harmless. `key()` is called once
   per method call (`QuotaService.java:28`), so one request sees one consistent date.
   Straddling midnight just means you see yesterday's number or today's, never a
   mixture.
4. **The counter goes past the daily limit.** It can, easily — see the note below.
   `Math.max(0, ...)` (`QuotaService.java:30`) keeps the report at exactly 0 instead
   of drifting into negative numbers.
5. **The program dies between the two Redis commands.** `recordSpend()` sends `INCR`
   (`QuotaService.java:23`) and then `EXPIRE` (`:24`) as two separate commands. If the
   program crashes in that gap, and it was the first spend of the day, the counter
   exists with no self-destruct timer and would sit in Redis forever. In practice the
   next successful spend that day re-arms it (`:24` runs on every spend). The Week 5
   design in `docs/PLAN.md:300-303` deliberately notes "EXPIRE to next midnight on
   first increment", so this gap is a known shape, not a surprise.

> **Suspicious #1 — this is a fuel gauge, not a fuel cutoff.** Nothing enforces the
> limit. `recordSpend()` increments unconditionally (`QuotaService.java:21-25`), and
> `SearchService.search()` never asks how many credits are left before searching — a
> search of the whole repository shows `remaining()` is called from exactly one place,
> `RetrievalController.java:33`, which is the reporting endpoint. So you can drive
> today's count to 5,000 against a limit of 1,000 (`application.yml:16-17`) and
> `POST /api/v1/search` will keep happily fetching. This is scoped, not forgotten:
> `CLAUDE.md`'s "Explicitly cut" list defers auth and rate limiting to Week 5, and
> `docs/PLAN.md:292-303` describes the real gates that arrive then — a per-user daily
> run cap that rejects with "daily limit reached", plus the per-domain token bucket
> still listed as unbuilt work in `docs/PLAN.md:83`. If you were expecting a wall, it
> is a window right now.

> **Suspicious #2 — one dead import.** `config/QuotaProperties.java:4` imports
> `org.springframework.context.annotation.Configuration`, but the file never uses
> `@Configuration` — the only annotation on the record is `@ConfigurationProperties`
> (`:6`). Completely harmless, compiles fine, but it is leftover scaffolding worth
> deleting.

> **Also worth knowing — the quota is global, not per person.** The key is built from
> the date and nothing else (`QuotaService.java:33`). There is no user identity anywhere
> in the code, because authentication is cut from weeks 1-4 by `CLAUDE.md`'s scope
> section. Per-user counting arrives in Week 5, keyed off the logged-in user's
> identifier (`docs/PLAN.md:289-290`).

## 6. If I changed X, what breaks?

1. **Remove the date from the key** (`QuotaService.java:33`) → the counter becomes a
   lifetime total that never resets. Worse, since `EXPIRE` re-arms on every spend
   (`:24`), the timer keeps sliding forward and the entry never dies while anyone is
   searching. Every search forever would come out of the same 1,000.
2. **Move `expire()` out of `recordSpend()`** (`QuotaService.java:24`) → counters
   created before the change would keep their timers, but every new counter would be
   created by `INCR` with no timer at all and would live forever. The current placement
   also matters: because the timer resets on every spend, a busy day's counter cannot
   expire out from under you mid-afternoon.
3. **Parse defensively but silently** — wrap `Integer.parseInt` (`QuotaService.java:29`)
   in a `try/catch` and treat a failure as 0 → a corrupted counter would quietly report
   full credits. You would be blind to the failure while over-spending. Crashing loudly,
   which is what happens today, is arguably the better behaviour here.
4. **Check `remaining() > 0` inside `SearchService.search()`** → this is the missing
   feature rather than a break. If you build it, put the check *before* the outbound
   call at `SearchService.java:62`, not after — otherwise you pay for the search and
   then refuse to hand over the results, which is the worst of both worlds.
5. **Change `daily-limit` in `application.yml:16-17`** → nothing in Redis changes at
   all. The stored value is *spent*, not *remaining* (`QuotaService.java:23`), so
   raising the limit to 5,000 instantly shows more credits left without touching a
   single stored entry. That is a real benefit of storing the count-up rather than the
   count-down.
