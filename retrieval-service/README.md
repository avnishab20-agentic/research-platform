# retrieval-service

**Port:** 8081

## What this is
The only service in this system allowed to search the web or fetch a page.
Everything about search — running the query, fetching the page, extracting
clean text from the HTML, caching the result, and making sure we don't hammer
any one site or blow through a search-API quota — lives here. `CLAUDE.md` calls
this "the quota boundary": no other service is allowed to call a search API
directly.

## How it will work (planned endpoints, per `docs/PLAN.md`)
```
POST /api/v1/search    queries[] → results[] + creditsSpent + cacheHits
POST /api/v1/extract   urls[]    → documents[] (text, tier, status)
GET  /api/v1/quota     credits remaining
```
- **Search** goes to SearXNG (a self-hosted metasearch engine — it queries
  several real search engines and merges the results, so we don't need our own
  API key for each one).
- **Extract** hands raw HTML to a small Python sidecar (`trafilatura`) that
  strips ads/navigation/boilerplate and returns clean article text.
- Both results are **cached in Redis** (cache-aside: check cache, miss, fetch,
  store with a TTL) so the same query or URL isn't paid for twice.
- A **Redis-backed token bucket** rate-limits outbound requests per domain, so
  we don't get IP-banned by a site we're scraping politely.

## Why it's designed this way
If five different agents could each call the search API whenever they wanted,
nobody could answer "how much search budget have we used this hour?" or
"why did site X block us?" without checking five places. Centralizing it in
one service means there's exactly one place that enforces the budget and the
politeness rules — and one place to look when something about search behaves
oddly.

Caching lives here too (not in each agent) for the same reason: two different
researcher agents asking the same question shouldn't cost twice.

## Why only this — the boundary
This service does **not** decide what to search for, and it does **not**
reason about the results — that's `agent-service`'s job. `retrieval-service`
is a dumb, fast, cacheable I/O layer: give it a query or a URL, it gives you
results or text back. Keeping "what to ask" and "how to fetch it" separate
means the fetching logic (caching, rate limits, retries) never has to know
anything about the AI side, and can be tested and reasoned about on its own.

## Status
Skeleton only — the Spring Boot app boots and `/actuator/health` responds, but
none of the endpoints above are implemented yet. That's Session 2 onward per
`docs/PLAN.md`.

> Note: this module's Java package is currently `com.comback.researchplatform`
> (missing the "e") — a leftover from the Initializr wizard typo that was
> fixed in the pom's `groupId` but never renamed in the actual package/folder
> structure. `agent-service` and `control-plane` correctly use
> `com.comeback.researchplatform`. Worth fixing for consistency before this
> package gets deeply referenced elsewhere.
