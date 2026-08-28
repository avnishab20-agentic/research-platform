# The Story of This Codebase — 90: Method Reference

*Every non-trivial method in the repo, grouped by class. Skipped as trivial: all Java
`record` accessors and constructors-of-records (they're data, not behavior — e.g.
`SearchRequest.java:6`, `ExtractorResult.java:3`), the three identical
`main()` methods (`RetrievalServiceApplication.java:11`,
`AgentServiceApplication.java:9`, `ControlPlaneApplication.java:9` — each is one
`SpringApplication.run` line), and Python's `health()` beyond its one-liner
(`main.py:23-25`).*

Legend: **CB** = called by · **CO** = calls out · **SE** = side effects · **FM** = failure modes.

**On this page:** [RetrievalController](#webretrievalcontrollerjava-the-front-door) · [SearchService](#searchsearchservicejava-the-librarian) · [QuotaService](#quotaquotaservicejava-the-meter) · [SourceTierResolver](#tiersourcetierresolverjava-the-grader) · [UrlNormalizer](#urlurlnormalizerjava-the-alias-detective) · [Hashing](#hashhashingjava-the-fingerprint-clerk) · [ExtractCacheKey](#extractextractcachekeyjava-the-label-maker) · [ExtractorClient](#extractextractorclientjava-the-unemployed-courier) · [Config factories](#config-factories-each-one-bean-method-called-by-spring-at-boot) · [Python sidecar](#extractormainpy-the-python-cast) · [Tests](#test-entry-points-who-runs-them-maven-surefire-mvn-test)

---

## `web/RetrievalController.java`: the front door

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `SearchResponse search(SearchRequest)` — `:22-24` | Accepts the search job and hands it, untouched, to the librarian. | CB: Tomcat thread (via `@PostMapping("/search")`, `:21`) → CO: `SearchService.search` | none of its own | whatever SearchService throws propagates as HTTP 500; malformed JSON → 400 before this method runs (Jackson, `:22`) |
| `ExtractResponse extract(ExtractRequest)` — `:26-29` | Stub: acknowledges an extraction job and returns an empty envelope. | CB: Tomcat (via `:26`) → CO: `List.of()` only | none — request body is parsed then ignored | cannot fail beyond 400 on bad shape |
| `QuotaResponse quota()` — `:31-34` | Reports credits left today. | CB: Tomcat (via `:31`) → CO: `QuotaService.remaining` | none of its own | QuotaService's Redis failures surface as 500 |

## `search/SearchService.java`: the librarian

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `SearchResponse search(SearchRequest)` — `:48-75` | The whole search pipeline: per-query cache-aside (check cache, fetch only on miss), then tier-grade, filter, and cap the merged results. | CB: controller `:23` → CO: `cacheKey` `:53`, `StringRedisTemplate.get` `:54`, `ObjectMapper.readValue` `:58`, `fetchResults` `:62`, `ObjectMapper.writeValueAsString` + `set(…,24h)` `:63`, `QuotaService.recordSpend` `:65`, `SourceTierResolver.resolveTier` `:70` | writes one Redis key per query with 24h TTL; increments the daily quota key (miss only) | Redis down → connection exception → 500 (no fallback, no try/catch in file); corrupt cached JSON → Jackson exception → 500 persistently until TTL; `maxResults ≤ 0` → `IllegalArgumentException` at `:72` *after* spend+cache already happened |
| `SearxngSearchResponse fetchResults(String query)` — `:41-46` | Performs the one outbound HTTP call: ask SearXNG for JSON results. | CB: `search` miss branch `:62` → CO: `searxngRestClient.get().uri(…).retrieve().body(…)` | outbound network call to `http://localhost:8080` | SearXNG down → `RestClientException` family → 500; SearXNG in HTML mode (missing `json` format, `searxng/settings.yml:9-12`) → conversion failure → 500. COULDN'T TRACE exact exception subclass (Spring internals) |
| `private static String cacheKey(SearchRequest, String query)` — `:77-81` | Mints the stable Redis label `search:v1:searxng:<sha256(normQuery\|freshness)[0:16]>`. | CB: `search` `:53` → CO: `normalizeQuery` `:79`, `Hashing.sha256Hex` `:80` | none (pure) | none — total function; `freshness` null becomes `""` at `:78` |
| `private static String normalizeQuery(String)` — `:83-87` | Trim, lowercase, collapse whitespace so spelling variants share one slot. | CB: `cacheKey` `:79` → CO: none | none (pure) | null input → NPE (callers never pass null today) |

## `quota/QuotaService.java`: the meter

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `void recordSpend()` — `:21-25` | Adds one credit to today's counter and (re)arms its 24h expiry. | CB: `SearchService.search` `:65` (miss only) → CO: `key()` `:22`, `redis.increment` `:23`, `redis.expire` `:24` | writes `quota:v1:<date>` in Redis | Redis down → exception propagates into the search flow → 500 (the search *succeeded upstream* but the caller still sees an error — spend is not wrapped in try/catch) |
| `int remaining()` — `:27-31` | Reports `dailyLimit − spent`, never below 0; missing counter reads as 0 spent. | CB: controller `:33` → CO: `key()` `:28`, `redis.get` `:28` | none — read-only | non-numeric value under the key → `NumberFormatException` `:29` → 500; Redis down → connection failure → 500 |
| `private String key()` — `:32-34` | Builds `quota:v1:` + today's date — the date *is* the daily reset. | CB: `recordSpend` `:22`, `remaining` `:28` → CO: none | none (pure; clock-dependent) | none |

## `tier/SourceTierResolver.java`: the grader

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `int resolveTier(String url)` — `:17-49` | Stamps a quality tier 1–4 on a URL: exact-match honor rolls first, globs second, unknown → 3. | CB: `SearchService` stream `:70`; `SourceTierResolverTest` (5 tests) → CO: `URI.create().getHost()` `:19`, properties lists `:31/:34/:39`, `matchesPattern` `:40` | none (pure) | unparseable/null host → returns 3 (`:20-22`), never throws; a YAML key/field mismatch (e.g. `tier4:` vs `tier4Patterns`) binds null → NPE at the `:39` loop *at request time* (Session 4's real bug; no boot-time validation exists) |
| `private boolean matchesPattern(String host, String pattern)` — `:53-56` | Converts one glob (`*`→`.*`, dots escaped) and regex-matches the host. | CB: `resolveTier` `:40` → CO: `String.matches` | none (pure) | malformed pattern → `PatternSyntaxException` → 500 (only reachable via bad YAML) |

## `url/UrlNormalizer.java`: the alias detective

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `static String normalize(String url)` — `:16-34` | Canonical spelling: lowercase scheme+host, drop `www.`, drop trailing `/`, clean the query, drop the fragment (by never reading it). | CB: `ExtractCacheKey.of` `:14`; `UrlNormalizerTest` (8 tests) → CO: `normalizeQuery` `:31` | none (pure) | `URI.create` on garbage → `IllegalArgumentException`; non-hierarchical URIs (e.g. `mailto:`) → `getHost()` null → NPE at `:21` (unreachable via SearXNG-sourced URLs today). Known blind spots, documented not handled: ports dropped (getPort never read, `:33` reassembles without it), percent-encoded `&` inside a param value would split wrongly (`getQuery` decodes, `:40` splits on `&`) |
| `private static String normalizeQuery(String)` — `:36-45` | Splits on `&`, drops blanks and noise params, sorts the rest, rejoins. | CB: `normalize` `:31` → CO: `paramName` `:42`, `isNoise` `:42` | none (pure) | null/blank → `""` (`:37-39`) by design |
| `private static String paramName(String)` — `:47-50` | Text before the first `=` — so noise filtering judges names, never values (`?q=gclid` survives). | CB: `normalizeQuery` `:42` → CO: none | none (pure) | none |
| `private static boolean isNoise(String name)` — `:52-54` | True for `utm_*` prefixed or exactly `fbclid`/`gclid`/`ref` (`:10-11`). | CB: `normalizeQuery` `:42` → CO: none | none (pure) | none |

## `hash/Hashing.java`: the fingerprint clerk

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `static String sha256Hex(String)` — `:13-21` | SHA-256 → lowercase hex; the shared fingerprint both cache keys use so they can't drift. | CB: `SearchService.cacheKey` `:80`, `ExtractCacheKey.of` `:14` → CO: JDK `MessageDigest`/`HexFormat` | none (pure) | `NoSuchAlgorithmException` is impossible for SHA-256 on a real JVM but is checked → would throw `IllegalStateException` `:18-20`; private ctor `:10-11` blocks instantiation |

## `extract/ExtractCacheKey.java`: the label maker

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `static String of(String url)` — `:13-15` | `extract:v1:` + first 16 hex of the *canonicalized* URL's SHA-256; prefix is a named constant so a version bump is one line (`:8`). | CB: **tests only today** (`ExtractCacheKeyTest`, 3 tests) — no production caller until `/extract` is wired → CO: `UrlNormalizer.normalize`, `Hashing.sha256Hex` | none (pure) | inherits UrlNormalizer's failure modes |

## `extract/ExtractorClient.java`: the unemployed courier

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `ExtractorResult extract(String url, String html)` — `:21-27` | POSTs `{url, html}` to the sidecar's `/extract` and parses the verdict. | CB: **nobody yet** (built, qualified, unhired) → CO: `extractorRestClient` → sidecar | outbound HTTP to `http://localhost:8000` | sidecar down → `RestClientException` family; sidecar 422/500 → `HttpServerErrorException`-family from `.retrieve()`; COULDN'T TRACE precise classes (Spring internals) |
| ctor `ExtractorClient(@Qualifier("extractorRestClient") RestClient)` — `:16-18` | Claims its specific courier-bean by name. | CB: Spring at boot → CO: none | none | missing/misnamed bean → boot failure |

## Config factories (each: one `@Bean` method, called by Spring at boot)

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `searxngRestClient(@Value("${searxng.base-url}"))` — `config/SearxngClientConfig.java:12-17` | Builds the SearXNG-pointed HTTP client. (Note the harmless double-paren typo in `@Value((…))` `:13`.) | CB: Spring container → CO: `RestClient.builder()` | creates a pooled client bean | missing YAML key → boot fails on unresolved placeholder |
| `extractorRestClient(@Value("${extractor.base-url}"))` — `config/ExtractClientConfig.java:12-17` | Same, for the sidecar (`application.yml:19-20`). | CB: Spring container → CO: `RestClient.builder()` | creates a pooled client bean | same; also the *existence* of this second bean is what forces both `@Qualifier`s |

## `extractor/main.py`: the Python cast

| Method | Purpose | CB → CO | SE | FM |
|---|---|---|---|---|
| `extract(req: ExtractIn) -> ExtractOut` — `main.py:28-44` | Strips boilerplate via trafilatura, attaches OK/PAYWALLED by the 200-char bar (`:7,:36`). | CB: uvicorn worker thread (route `:28`) → CO: `trafilatura.extract` `:30`, `trafilatura.extract_metadata` `:31` | none — stateless; no disk, no cache | trafilatura returning `None` → PAYWALLED (graceful, `:36`); any library exception → bare 500 (no try/except in file). COULDN'T TRACE trafilatura's internal raise behavior on pathological input |
| `health()` — `main.py:23-25` | The pulse Docker's healthcheck knocks on. | CB: Docker daemon every 10s (`docker-compose.yml:69-73`) → CO: none | none | cannot fail |

## Test entry points (who runs them: Maven Surefire, `mvn test`)

- `contextLoads()` ×3 — `RetrievalServiceApplicationTests`, `AgentServiceApplicationTests`,
  `ControlPlaneApplicationTests`: boot the full Spring context; retrieval's proves both
  RestClient beans disambiguate; control-plane's requires live Postgres.
- `SourceTierResolverTest` — 5 tests, `tier/` package (surefire-verified counts).
- `UrlNormalizerTest` — 8 tests, `url/UrlNormalizerTest.java` — one per canonicalization rule.
- `ExtractCacheKeyTest` — 3 tests, `extract/ExtractCacheKeyTest.java` — prefix+length shape,
  equivalent-URL collision (`:19-23`), meaningful-params divergence (`:26-30`).
