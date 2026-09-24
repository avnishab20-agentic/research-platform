# retrieval-service

**Port 8081.** The only part of the system allowed to touch the web. It
searches, downloads pages, turns them into clean text, caches everything, and
makes sure we stay within our search budget and don't overload any website.
It also serves the web page you use (`src/main/resources/static/`).

## Endpoints

| Endpoint | Takes | Returns |
|---|---|---|
| `POST /api/v1/search` | `queries[]`, `maxResults`, `minTier`, `freshness` | results (url, title, snippet, trust tier), `creditsSpent`, `cacheHits` |
| `POST /api/v1/extract` | `urls[]` | one document per URL: clean text, tier, status |
| `GET /api/v1/quota` | — | searches left today |

When the daily search quota is used up (and guardrails are in `ENFORCE` mode),
`/search` returns **429 Too Many Requests**.

## How it works

**Search** (`search/SearchService`)
1. The query is normalised (trimmed, lower-cased, spaces collapsed) and turned
   into a cache key.
2. If it's cached in Redis, the cached result is returned for free.
3. Otherwise, one request takes a short Redis lock and does the real search
   through SearXNG (or Tavily, if `TAVILY_API_KEY` is set). That costs one
   credit, and the result is cached for 24 hours. Identical requests arriving
   at the same moment wait for that result instead of searching again.
4. Each result gets a **trust tier** from its website (`tier/SourceTierResolver`,
   configured under `source-tiers:`): 1 official, 2 established news,
   3 unknown, 4 low-trust.

**Extract** (`extract/ExtractService`): each URL is handled in parallel:
1. Check the cache (pages are cached for 7 days).
2. Wait for this website's rate limit (`ratelimit/DomainRateLimiter`, a token
   bucket stored in Redis and run as a Lua script, so every copy of the
   service shares the same limit).
3. Download the HTML (`extract/PageFetcher`): only `http`/`https`, never an
   address inside a private network, at most 2 MB.
4. Send it to the Python **extractor** sidecar, which strips menus and ads. At
   most 4 extractions run at once.

A failed URL never breaks the whole batch. It comes back with a status:
`OK`, `PAYWALLED`, `UNREACHABLE`, `TOO_LARGE`, `RATE_LIMITED`,
`BLOCKED_SCHEME` or `BLOCKED_PRIVATE_NETWORK`.

**URL normalisation** (`url/UrlNormalizer`): different spellings of the same
page share one cache entry. For example,
`https://www.TheHindu.com/news/?utm_source=x#top` and
`https://thehindu.com/news` are treated as the same page.

## Configuration (`application.yml`)

| Block | Controls |
|---|---|
| `guardrails.retrieval` | daily search quota, private-network blocking, allowed URL schemes |
| `rate-limit` | per-website token bucket: burst size, refill rate, wait attempts |
| `extractor` | the sidecar's address, page size cap, page cache time, timeouts, how many extractions at once |
| `source-tiers` | which domains are tier 1, tier 2, and tier 4 |
| `searxng`, `tavily` | where the search engines are |

## Why a separate service

If every agent could search on its own, nobody could answer "how much have we
spent today?" or "why did this website block us?" without checking several
places. With one service in charge, the budget, the cache and the politeness
rules are all in one place.

This service never decides *what* to search for or what the results mean.
That's `agent-service`'s job.

## Run and test

```bash
mvn -pl retrieval-service -am test          # unit tests (no Redis or network needed)
mvn -pl retrieval-service spring-boot:run   # needs Redis, SearXNG, extractor (docker compose up -d)
```
