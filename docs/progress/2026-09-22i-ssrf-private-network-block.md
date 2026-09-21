# 2026-09-22 (part 9) — private-network blocking: guardrail #6, the SSRF gap closed

Next item, fully done: the one genuine gap found while scoping the guardrail
tripwire eval — `guardrails.retrieval.blockPrivateNetworks` existed as a
config field but nothing read it. A researcher agent (or a page it links to)
handing `PageFetcher` a URL like `http://10.0.0.1/` or `http://169.254.169.254/`
(a cloud metadata endpoint) would have been fetched like any other page.

## The mechanism

`PageFetcher.fetch()` now resolves the URL's host via `InetAddress.getByName`
before issuing the HTTP request, and checks the resolved address against four
`InetAddress` predicates: `isLoopbackAddress()` (127.x, ::1),
`isSiteLocalAddress()` (10.x, 172.16-31.x, 192.168.x), `isLinkLocalAddress()`
(169.254.x -- this is also the cloud-metadata-endpoint range on AWS/GCP/Azure,
so it's worth blocking even though PLAN only names the RFC1918 ranges), and
`isAnyLocalAddress()` (0.0.0.0). A match short-circuits before any network
call, returning a new `FetchedPage` status: `BLOCKED_PRIVATE_NETWORK`.

**This check is unconditional when `blockPrivateNetworks` is true -- it does
not read `guardrails.mode`.** Every other guardrail built so far (quota,
run-level ceilings) has an ENFORCE/SHADOW split, because those are soft usage
limits where "log it and let it through anyway" is a reasonable rollout
step. SSRF protection isn't that kind of guardrail: a SHADOW mode that logs a
request to `169.254.169.254` and then makes it anyway defeats the entire
point of having the check. Recorded as a deliberate asymmetry, not an
oversight -- the boolean flag itself is still the on/off switch.

**DNS resolution failures and hostless URLs are NOT blocked here.** If
`InetAddress.getByName` throws (bad hostname, DNS down) or the URL has no
host, `targetsPrivateNetwork()` returns `false` and lets the real fetch
attempt run -- it will report `UNREACHABLE` on its own terms, the same as
today. The private-network check only ever adds a new way to say "no," never
a new way to fail an otherwise-fine request.

## Wiring

`PageFetcher`'s constructor gained a third parameter, `GuardrailProperties`,
alongside the existing `ExtractProperties`. `retrieval-service` already has
`@EnableConfigurationProperties(GuardrailProperties.class)` registered (from
the earlier quota-enforcement pass), so no new Spring wiring was needed --
the bean was already there to inject.

## Tests

**`PageFetcherTest`** gained 5 new tests (6 -> 11): loopback URL blocked,
site-local (`10.0.0.1`) URL blocked, link-local (`169.254.169.254`, the
cloud-metadata trap) URL blocked, a public URL (`example.com`) never blocked
regardless of the flag, and -- with the guardrail explicitly turned off via a
second test fixture (`fetcherWithBlocking(false)`) -- the same `10.0.0.1` URL
now goes through to the mock server and returns `OK`. That last test is the
one that proves the flag actually gates the behavior rather than the check
always firing.

Full `retrieval-service` suite: **74/74 green**. No Docker needed -- the
blocked-URL tests never reach the network at all, and the public-URL test
uses the existing `MockRestServiceServer`.

## Next

The guardrail tripwire eval (PLAN's second eval) can now be built covering
all 6 of PLAN's cases for real, since this was the only one with no
underlying decision logic to assert against. Next item to build fully:
`GuardrailTripwireEval`, asserting each of the 6 cases fires correctly as a
set -- 300KB document (`PageFetcher`'s existing size guard), `10.0.0.1` URL
(this pass), run past `max-searches` (`RunUsageGuard.trySearch`), planner
emitting 9 sub-questions (`PlannerService`'s `maxFanOut` truncation),
researcher past 90s (`ResearcherService`'s wall-clock budget), quota at zero
(`QuotaService.checkBudget`).
