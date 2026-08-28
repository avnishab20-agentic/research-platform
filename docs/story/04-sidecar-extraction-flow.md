# The Story of This Codebase — 04: The Extractor Sidecar Flow

*Flow 5 of the inventory — a working service with a lonely job. Today it's only ever
hired by hand (curl); the Java courier who's supposed to hire it (`ExtractorClient`)
sits qualified but unemployed. This chapter narrates the Python side of the wall.*

**On this page:** [1. Trigger](#1-what-triggers-this) · [2. Diagram](#2-the-journey-as-a-diagram) · [3. Narration](#3-the-narration) · [4. Framework magic](#4-framework-magic-roundup) · [5. Unhappy paths](#5-unhappy-paths) · [6. If I changed X](#6-if-i-changed-x-what-breaks)

---

## In plain English (30 seconds)

There is one small program in this project that is not written in Java. It is written
in Python, and it lives in its own little box next door. Its only job is to take a
messy web page and hand back just the article text — no menus, no ads, no cookie
banners. You give it the page; it never goes and gets the page itself. If what comes
back out is too short, it says "this was probably behind a paywall."

After this chapter you will know what that little box is, who starts it, who checks
the incoming request before our code runs, how the cleaning happens, and why the
200-character line in the sand matters.

---

## 1. What triggers this

### First, some words you need

A few terms show up in the very first sentence, so let's get them out of the way.

**HTTP** stands for HyperText Transfer Protocol. It is just the agreed set of rules
computers use to ask each other for things over a network. A **POST** is one kind of
HTTP request: "here is some data, please do something with it." (The other common one
is GET: "please give me something.")

**JSON** stands for JavaScript Object Notation. It is a plain-text way to write data
that both sides can read, made of names and values in curly braces. Every message in
this chapter travels as JSON text.

**URL** stands for Uniform Resource Locator — the address of a page, like
`https://thehindu.com/news/rbi`.

**HTML** stands for HyperText Markup Language — the raw code a web page is actually
made of, tags and all. It contains the article *and* the navigation bar, the advert
slots, the "accept cookies" popup, and the comment section.

An **endpoint** is one specific address on a service that you can send a request to,
paired with the code that answers it. `/extract` is an endpoint. `/health` is another.

### The actual trigger

Someone sends an HTTP POST request to `http://localhost:8000/extract`. The body of
that request is JSON, and it must have exactly the two fields the Python code declares
in a small class called `ExtractIn` (`extractor/main.py:10-12`):

```json
{ "url": "https://thehindu.com/news/rbi", "html": "<html>...whole raw page...</html>" }
```

Read in plain English, the request says: *here's a web page I already downloaded;
throw away the menus, ads, cookie banners, and comment sections; give me back the
article.*

### The division of labour, and why it matters

Look carefully at what the caller is sending. It sends the `html` — meaning the caller
already went out to the internet and downloaded the page. The Python service is only
handed the finished text.

Here is the whole chapter in one sentence: **the caller fetches, the sidecar cleans.**

This box never opens a network connection outward. Nowhere in `extractor/main.py` does
anything call out to the internet — the only two library calls in the whole request
path are handed a string that arrived in the request body (`extractor/main.py:30-31`).

That single fact explains the shape of the answer it gives. The verdict is only ever
`OK` or `PAYWALLED` (`extractor/main.py:36`). Compare that with the wider vocabulary
the project's plan lists for a document's status: `OK | PAYWALLED | ROBOTS_DENIED |
UNREACHABLE | TOO_LARGE` (`docs/PLAN.md:64`).

The three missing ones are missing on purpose:

| Status | What it means | Why Python can't say it |
|---|---|---|
| `UNREACHABLE` | the site never answered | you only see that while dialling, and Python never dials |
| `ROBOTS_DENIED` | the site's `robots.txt` file — a plain-text notice at a website's root asking automated visitors to stay out of certain paths — said don't | you only read `robots.txt` when you visit the site |
| `TOO_LARGE` | the download was enormous | you only see the size while downloading |

All three are only visible at fetch time, and fetch time happens in Java
(`docs/PLAN.md:67-69`). This split was written down as a deliberate deviation from the
plan, not an oversight (`CLAUDE.md:360`).

> **Why bother splitting it at all?** Because of a rule the project holds to: all
> outbound web access must funnel through one service, `retrieval-service`, so that
> credits and caching can be counted in exactly one place (`CLAUDE.md:82`). If this
> Python box started fetching pages too, there would be two doors to the internet and
> the counting would be a lie.

### And what is a "sidecar", anyway?

A **container** is a packaged, self-contained copy of a program plus everything it
needs to run — its own Python, its own libraries, its own filesystem. It behaves like
a tiny separate computer, even though it's sharing yours.

A **sidecar container** is a container that exists purely to do one small supporting
job for a main service. Like the sidecar on a motorbike: it doesn't drive anywhere on
its own, it just rides along and carries something the bike can't.

Here, the main service is the Java `retrieval-service`, and the sidecar is this Python
box. Why not just do the cleaning in Java? Because the best library for the job,
`trafilatura`, is a Python library. Rather than fight that, the project gives Python a
tiny box of its own and talks to it over HTTP.

In the cast list of this guide, **the Python sidecar is the specialist who strips the
ads off a page**. He is very good at one thing and does nothing else.

## 2. The journey as a diagram

Two names in the diagram below get proper introductions in section 3, but here's the
one-line version so the picture reads: **uvicorn** is the waiter who answers the phone
when a request arrives and hands it to our code, and **trafilatura** is the library
that does the actual page cleaning.

```text
Caller - curl today, ExtractorClient one day
    │  POST /extract, JSON holding url and html
    ▼
uvicorn - the Python web server in the box
    │
    │  note: pydantic checks the JSON shape first.
    │  Wrong shape means HTTP 422 and our code never runs.
    │
    │  call extract on a worker thread
    ▼
extract function in main.py
    │  clean this html
    ▼
trafilatura - the page-cleaning library
    │  replies: article text, or None if nothing usable
    ▼
extract function in main.py
    │  read the metadata of this html
    ▼
trafilatura - the page-cleaning library
    │  replies: title and date, or None
    ▼
extract function in main.py
    │
    ├── cleaned text is 200 characters or more
    │     ► reply to uvicorn: status OK, plus text, title, publishedAt
    │
    └── text is shorter, empty, or None
          ► reply to uvicorn: status PAYWALLED
    │
    ▼
uvicorn - the Python web server in the box
    │  replies: one JSON reply
    ▼
Caller - curl today, ExtractorClient one day
```

## 3. The narration

### Step 0 — who answers the phone? *(framework magic, Python edition)*

Nothing in `extractor/main.py` says "start listening on port 8000." So who does?

The answer is a separate program that we did not write, and it is named in the very
last line of the container's build recipe. That recipe is the **Dockerfile** — a short
list of steps for building the box that holds our program, read top to bottom.

Here it is, all seven lines:

```dockerfile
FROM python:3.12-slim          # start from a stripped-down Python 3.12 image
WORKDIR /app                   # do everything from here on inside /app
COPY requirements.txt .        # copy the list of libraries in first
RUN pip install --no-cache-dir -r requirements.txt   # install them
COPY main.py .                 # only then copy our actual code
EXPOSE 8000                    # document that this box speaks on port 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

(`extractor/Dockerfile:1-7`.)

That copy order on lines 3–5 is deliberate. Installing libraries is slow; copying one
small Python file is instant. By copying the library list first and installing before
touching `main.py`, a rebuild only re-runs the slow install when the library list
actually changed (`CLAUDE.md:333`). The library list itself is three lines —
`fastapi`, `uvicorn`, and `trafilatura`, each pinned to a version range
(`extractor/requirements.txt:1-3`).

**`CMD`** is the last line and it means "when this container starts, run this."
(`extractor/Dockerfile:7`.) There is no startup script, no supervisor, no service
manager. The container *is* this one process.

**uvicorn** is that process. It is a web server: think of it as the restaurant's
waiter, standing by the phone all day, waiting for requests to arrive, and then
carrying each one over to your code. A "network port" is just a numbered door
that the phone line plugs into, so the waiter knows which door to watch. `main:app`
tells it where your code is — "import the file `main.py`, and inside it find the
object named `app`."

That object is built on line 5: `app = FastAPI(title="extractor")`
(`extractor/main.py:5`).

**FastAPI** is the Python web framework — the waiter's boss, who keeps a list of which
door each request should be carried to. It turns ordinary Python functions into HTTP
endpoints. At startup, uvicorn imports `main.py`, which runs it top to bottom, which
means the two `@app...` lines get executed. Those lines register routes into FastAPI's
internal routing table: `/health` answered by `health` (`extractor/main.py:23-24`), and
`/extract` answered by `extract` (`extractor/main.py:28-29`).

That routing table is the exact moral equivalent of the one Spring builds on the Java
side from `@GetMapping` and `@PostMapping`. Different language, same trick: a lookup
table from "address that was asked for" to "function to call."

**And what is a decorator?** In Python, a line starting with `@` written directly above
a function is a **decorator**. It is a function that takes your function and does
something with it. It runs *once*, when the file is first imported — not on every
request. `@app.post("/extract")` (`extractor/main.py:28`) means roughly: "hey FastAPI,
please remember that a POST to `/extract` should be answered by the function written
below me." That is the entire magic. It's a sticky note, applied at startup.

Now the flag on the `CMD` line that matters more than it looks: **`--host 0.0.0.0`**.

By default, uvicorn listens only on `127.0.0.1`, called **loopback** — an address that
means "this machine, and only this machine." Inside a container, "this machine" means
*inside the box*, so a loopback-only server is invisible from outside the box.
`0.0.0.0` means "listen on every network address I have," which includes the one the
outside world reaches.

That matters because the compose file maps the host's port 8000 to the container's
port 8000 (`docker-compose.yml:67-68`). Without `--host 0.0.0.0`, that mapping would
knock on a door with nobody behind it.

Finally, who actually *calls* our function? A **thread** is one line of execution — one
worker doing one thing at a time. FastAPI runs endpoint functions written as a plain
`def` (as ours are, at `extractor/main.py:24` and `:29`) on a small pool of background
worker threads, precisely so a slow one doesn't freeze the whole server. So the honest
answer to "who calls `extract()`?" is: *a uvicorn worker thread, one per in-flight
request, spawned by a server we didn't write.*

### Step 1 — the bouncer checks IDs

Before a single line of our code runs, the incoming JSON is inspected.

**Validation** just means checking that data is the right shape before you trust it.
Is the field there at all? Is it the right type? If not, reject it now, politely, with
a clear message — rather than letting our code blow up later on a missing field.

The library that does this is **pydantic**. Think of it as the bouncer standing at the
front door with the guest list. It works from ordinary Python classes that list the
fields and their types — the list says who's allowed in and what they have to be
wearing. Ours is four lines:

```python
class ExtractIn(BaseModel):
    url: str
    html: str
```

(`extractor/main.py:10-12`.) `str` means "text." So: both `url` and `html` must be
present, and both must be text.

FastAPI knows to apply this because of how the function is declared:
`def extract(req: ExtractIn) -> ExtractOut` (`extractor/main.py:29`). The `: ExtractIn`
part is a **type hint** — Python's way of writing down what type a value is meant to
be. FastAPI reads that hint and wires up the check automatically.

If the JSON doesn't match, FastAPI replies with HTTP status **422**, which means
"I understood your request, but the contents are unprocessable" — along with a
field-by-field explanation of what was wrong. All of that is generated by the
framework. We wrote none of it.

This is the same job that Jackson (the Java library that turns JSON into Java objects)
plus Spring do at the Java front doors.

### Step 2 — the specialist reads the page

Now our code finally runs. The whole function is `extractor/main.py:28-44` — seventeen
lines including blanks. Two library calls do essentially all the real work.

**Call one — get the article text** (`extractor/main.py:30`):

```python
text = trafilatura.extract(req.html, include_comments=False, include_tables=False)
```

**trafilatura** is the specialist. Its one talent is looking at a full web page and
working out which part of it is the actual article. Everything else — navigation,
adverts, sidebars, footers, "related stories" — is **boilerplate**, the repeated
furniture that surrounds content on every page of a site. Trafilatura throws the
boilerplate away and returns just the prose, as plain text.

If it can't find an article at all, it returns `None`. In Python, `None` means "no
value" — the same idea as `null` in Java. That possibility is the hinge the next step
turns on, so hold onto it.

The two switches are turned off on purpose. `include_comments=False` drops the
comment section, and `include_tables=False` drops tables. This project needs clean
prose for later fact-checking, not an argument thread underneath the article.

**Call two — get the title and date** (`extractor/main.py:31`):

```python
meta = trafilatura.extract_metadata(filecontent=req.html)
```

This returns an object holding the page's headline, publication date and similar
details — or nothing useful, if the page didn't declare any.

Both are then unpacked defensively (`extractor/main.py:33-34`):

```python
title = meta.title if meta and meta.title else None
published_at = str(meta.date) if meta and meta.date else None
```

Read left to right, each line says: *use `meta.title` if we actually got a `meta`
object and it actually has a title; otherwise use `None`.* Without that guard, a page
with no metadata would make `meta` be nothing at all, and reaching for `.title` on
nothing would crash the request. `str(...)` on the date just converts whatever date
object trafilatura produced into plain text, so it can travel as JSON.

### Step 3 — the verdict

One line decides the whole outcome (`extractor/main.py:36`):

```python
status = "OK" if text and len(text.strip()) >= MIN_TEXT_CHARS else "PAYWALLED"
```

This is a **ternary** — a compact one-line if/else that produces a value. Unpacked into
ordinary Java-ish shape it reads:

```
if (text exists) and (text, with leading/trailing whitespace removed, is at least
    MIN_TEXT_CHARS characters long)
    -> "OK"
else
    -> "PAYWALLED"
```

`.strip()` removes whitespace from both ends, so a page that yields nothing but blank
lines can't sneak through on length. `len(...)` is the character count.

And the threshold itself is a single named constant near the top of the file:
`MIN_TEXT_CHARS = 200` (`extractor/main.py:7`).

**Why call the short case "PAYWALLED"?** Because think about what has actually
happened. Someone successfully downloaded a page — the fetch worked — yet after
stripping the furniture there is barely any article underneath. That is almost never
random noise. It is a paywall teaser, a cookie wall, a "please log in" screen, or a
page whose real content only appears after JavaScript runs in a browser we don't have.

Naming that `PAYWALLED` rather than quietly returning empty text protects everything
downstream. A blank document that says "OK" would sail into the pipeline and only fail,
confusingly, much later during fact-checking.

> **Sharp edge — read straight off the code:** the bar is exactly 200 characters
> (`extractor/main.py:7` and `:36`). A genuine 194-character article is labelled
> PAYWALLED. Real borderline pages will occasionally wear the wrong label. That's
> acceptable for version 1; revisit if false positives start showing up.

**Assembling the reply.** Finally one response object is built and returned
(`extractor/main.py:38-44`): the `url` echoed straight back, plus `title`, `text`,
`publishedAt`, and `status`. Its shape is declared by a second pydantic class,
`ExtractOut` (`extractor/main.py:15-20`), where every field except `status` is allowed
to be absent.

Echoing the URL back looks redundant — the caller obviously knows what it asked about.
It isn't. This service keeps no memory of anything between requests, and a caller that
fires several extractions at once needs a way to match each answer to the question that
produced it. The echoed URL is that label.

Then pydantic turns the object into JSON, using the declared field names exactly as
written. And that last detail is quietly load-bearing across two languages.

Look at line 19: the field is spelled `publishedAt` (`extractor/main.py:19`) — the
capital-A-in-the-middle style called **camelCase**, which is Java's habit, not Python's
(Python would normally write `published_at`, and indeed the local variable on line 34
*is* `published_at`). It's camelCase in the reply on purpose. The Java record waiting
on the other side of the wall is
`ExtractorResult(String url, String title, String text, String publishedAt, String status)`
(`extract/ExtractorResult.java:3`). Matching names mean the JSON maps onto the Java
object field-for-field with zero configuration.

*(A **record** is Java's short way of declaring a small, immutable data holder — you
write the field list once and get the constructor and accessor methods for free. This
project prefers records over the Lombok library for that job.)*

### Step 4 — the health pulse

There is a second endpoint, and it is three lines
(`extractor/main.py:23-25`):

```python
@app.get("/health")
def health():
    return {"status": "ok"}
```

It doesn't check anything. It just answers. That's the point: if the process is wedged
or dead, it *can't* answer, and the silence is the signal.

Its caller is not a human. Docker — the tool that builds and runs the little boxes — is
configured to run a **healthcheck**: a command it runs inside the container on a
schedule, like a knock on the door asking "are you still awake?" The answer (or the
silence) sets the container's status. Ours is defined at `docker-compose.yml:69-73`:

```yaml
healthcheck:
  test: ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://localhost:8000/health')\""]
  interval: 10s
  timeout: 5s
  retries: 5
```

Every 10 seconds, Docker runs that little Python one-liner inside the box. If the call
succeeds, the container is marked `healthy`; after 5 consecutive failures, `unhealthy`.

Why a Python one-liner and not plain `curl`? Because the base image is
`python:3.12-slim` (`extractor/Dockerfile:1`) — the "slim" variant, stripped of
non-essential tools, and `curl` is one of the things that didn't make the cut
(`CLAUDE.md:334`). So the healthcheck uses the one tool the image definitely has:
Python itself.

This is the same "invisible caller" pattern as Spring Boot's `/actuator/health` on the
Java services, transplanted into Python-land.

## 4. Framework magic roundup

"Framework magic" means: things that definitely happen, that nobody in this repository
wrote a line of code to cause.

| Invisible thing | Who actually does it | When |
|---|---|---|
| Importing `main.py` and reading its `@app` decorators into a route table | uvicorn, the Python web server, at container start | once |
| Checking the incoming JSON against `ExtractIn`, and returning HTTP 422 if it's wrong | pydantic, the validation library, called by FastAPI | every request |
| Running `extract()` on a background worker thread so a slow page can't freeze the server | uvicorn's worker thread pool | every request |
| Turning the returned `ExtractOut` object back into JSON text | pydantic, driven by the `-> ExtractOut` hint on `extractor/main.py:29` | every request |
| Declaring the container `healthy` or `unhealthy` | the Docker daemon — the background program that runs containers — executing the compose healthcheck | every 10s (`docker-compose.yml:71`) |
| Serving the app at all | the `CMD` line, `extractor/Dockerfile:7`. There is no supervisor script; the container **is** the process | container start, once |
| Building the image from source instead of downloading a ready-made one | Docker Compose, from `build: ./extractor` (`docker-compose.yml:65`) | first `docker compose up`, then on rebuild |

## 5. Unhappy paths

1. **Malformed JSON, or a missing field.** pydantic rejects it with HTTP 422 before our
   first line runs (`extractor/main.py:10-12`). A clean, informative refusal, entirely
   framework-generated.

2. **Garbage HTML** — binary junk, an empty string, a page that isn't a page.
   `trafilatura.extract` (`extractor/main.py:30`) typically returns `None`, the ternary
   on line 36 falls to the `else`, and the caller gets `status: "PAYWALLED"` with
   `text: null`. Graceful by design.

   > **COULDN'T TRACE:** whether some pathological input could make trafilatura itself
   > raise an error rather than returning `None`. That behaviour lives inside the
   > third-party library, not in this repo. What *is* verifiable here: there is **no
   > `try`/`except` anywhere in `extractor/main.py`** — I read all 44 lines. So any
   > exception that does escape becomes FastAPI's default bare HTTP 500, which means
   > "something broke on the server side" with no useful detail for the caller.

3. **Container overloaded.** Imagine twelve extractions arriving at once. Each takes a
   worker thread, and parsing a page is **CPU-bound** — it is limited by raw processor
   work, not by waiting on the network — so they genuinely compete and latency stacks
   up fast.

   The sidecar has no queue and no limit of its own. Nothing in `extractor/main.py`
   throttles anything. Protection has to come from the caller, which is exactly why the
   plan reserves a `Semaphore(4)` on the Java side (`docs/PLAN.md:97-98`). *(A
   **semaphore** is a counter that only lets a fixed number of workers through at once —
   like a bouncer holding four cloakroom tickets. No ticket, you wait.)* That code
   isn't written yet.

4. **Healthcheck fails** because the process is wedged. Docker marks the container
   `unhealthy`… and that is the end of the story. There is **no `restart:` policy on
   any service in `docker-compose.yml`** — I checked all five services; the key does not
   appear. Unhealthy is a label, not an ambulance. Somebody has to notice and restart
   it by hand.

**What state is left behind after any of these?** None. The sidecar has no database, no
cache, no files it writes, no memory of previous requests — every request is entirely
self-contained (`extractor/main.py:28-44` reads only from `req`). There is nothing to
clean up and nothing to roll back. All the mess after a failure lives on the caller's
side of the wall.

## 6. If I changed X, what breaks?

1. **Rename `publishedAt` to `published_at`** (`extractor/main.py:19`).
   Nothing breaks today, because nothing in Java calls this service yet. But on the day
   the Java side is wired in, `ExtractorResult.publishedAt`
   (`extract/ExtractorResult.java:3`) would quietly deserialize as `null` forever —
   Jackson matches JSON keys to Java record fields **case-sensitively** and character
   for character. No error, no warning, just a permanently empty date. This exact
   near-miss already happened once: during Session 10 the Java record was first typed
   with capitalized `PublishedAt`/`Status`, which would have compiled perfectly and
   silently produced nulls (`CLAUDE.md:342`). It was caught before wiring, by luck as
   much as anything.

2. **Lower `MIN_TEXT_CHARS` to 50** (`extractor/main.py:7`).
   Paywall stub pages — "Subscribe to keep reading", plus three teaser paragraphs —
   start clearing the bar and coming back as `OK`. Junk documents then flow into the
   pipeline, and the fact-checking stage later spends real money asking a language model
   to grade claims made of teaser text.

3. **Bind uvicorn to `127.0.0.1` instead of `0.0.0.0`** (`extractor/Dockerfile:7`).
   This is the nastiest one. The container would report **healthy**, because its own
   healthcheck runs *inside* the container (`docker-compose.yml:70`), where loopback
   works fine. Meanwhile every caller from outside the box gets "connection refused."
   A green checkmark on a dead service is the worst kind of bug: the dashboard lies to
   you.

4. **Add a second endpoint that fetches URLs itself.**
   It would work, and it would quietly wreck the architecture. `retrieval-service` is
   meant to be the single door to the web, so that search credits and caching are
   counted in exactly one place (`CLAUDE.md:82`). Two doors means the count is wrong and
   nobody notices until the bill arrives. The dishwasher doesn't do the grocery
   shopping.

5. **Delete the `EXPOSE 8000` line** (`extractor/Dockerfile:6`).
   Almost nothing breaks — and that's worth knowing. `EXPOSE` is documentation; it
   records the port the image intends to use. The thing that actually makes port 8000
   reachable from your laptop is the port mapping in the compose file
   (`docker-compose.yml:67-68`), plus `--host 0.0.0.0` on the `CMD`. Deleting `EXPOSE`
   costs you clarity, not connectivity.
