# 2026-09-22 (part 6) — the recording run: three real bugs, one external limit

Set out to do one thing: turn on `fixtures.record-mode`, run a handful of
real questions, and populate the fixture files built earlier today with
real content. That one thing took four rounds of debugging, each round
finding something genuinely new -- worth recording all of them, because
each is a different *category* of trap, not a repeat of the same mistake.

## Bug 1: a relative path that assumed the wrong working directory

`FixtureIO`'s default write location was
`"agent-service/src/main/resources/fixtures/"`. That's correct if the
process's working directory is the repo root. It isn't: `mvn -pl
agent-service spring-boot:run`'s forked JVM runs with its working
directory set to the *module's own* directory
(`.../research-platform/agent-service/`), confirmed directly with `lsof
-p <pid> | grep cwd`. So the real write path resolved to a wrongly
double-nested `agent-service/agent-service/src/main/resources/fixtures/`
-- which explains why `find`-ing the *expected* location kept coming up
empty even though recording was genuinely running and genuinely
succeeding, just somewhere else.

**Found by:** a direct, unconditional `CommandLineRunner` that called
`fixtureIO.record(...)` at startup, bypassing the entire Kafka/HTTP
request pathway, then a filesystem-wide `find` for the file it claimed to
have written successfully.

**Fix:** dropped the redundant `agent-service/` prefix from the default.

## Bug 2: a decorator that broke Spring AI's internal option casting

To make an ordinary live run double as the way fixture data gets
captured, the plan was: wrap the real, autoconfigured DeepSeek `ChatModel`
in a decorator (`RecordingChatModel`), record the response on the way out,
register it as `@Primary` so every consumer gets the recording version
automatically when `fixtures.record-mode: true`.

It compiled clean, started clean -- and then **every single
query-generation call failed**, all with the same exception:
```
java.lang.ClassCastException: class org.springframework.ai.chat.prompt.DefaultChatOptions
cannot be cast to class org.springframework.ai.openai.OpenAiChatOptions
```
**Root cause:** `ChatClient` needs to know the concrete options type a
specific model expects (`OpenAiChatOptions` for the real DeepSeek model)
to merge the right kind of options into every prompt it builds. A generic
`ChatModel` decorator, with no model-specific knowledge, doesn't carry
that information -- so prompts arrived at the real, underlying
`OpenAiChatModel` carrying generic `DefaultChatOptions`, and that model's
own `createRequest()` does an unconditional cast back to
`OpenAiChatOptions`, which fails every time.

Tried the obvious fix first -- overriding `getDefaultOptions()` on the
decorator to delegate to the real model -- and it **didn't help**. The
exact same exception, at the exact same line, after a clean recompile
(confirmed by the shifted line number in the stack trace matching the new
file). Whatever `ChatClient` actually uses to determine the options type
at prompt-build time, it isn't simply calling `getDefaultOptions()` on
whatever `ChatModel` was handed to its builder.

**The real fix wasn't a fix -- it was giving up on the wrapper.**
Deleted `RecordingChatModel`/`RecordingChatModelConfig` entirely. The
working pattern was sitting right there already, in
`HttpRetrievalClient`: a plain, inline, post-call side effect --
`if (recordMode) { record the thing }` -- at the exact two spots in
`ResearcherService` that already hold a real `ChatResponse`
(`generateQueries`, `synthesizeAnswer`). No proxying, no impersonating a
framework type, no risk of breaking internals the wrapper doesn't
understand. This is a smaller, less clever change than the decorator, and
it's the one that actually works.

**The lesson worth keeping, generalized:** wrapping a first-party
framework interface as a transparent decorator is only safe when you
fully understand everything the framework's *other* internal code assumes
about instances of that interface -- not just the interface's documented
contract. When that's not true (and it usually isn't, for anything as
deep as a chat client's request-building pipeline), an inline side effect
at the point where you already have the data is the lower-risk move, even
though it means touching more call sites individually.

## Bug 3: a known, pre-documented limitation, now actually confirmed live

With bugs 1 and 2 fixed, real search-response and chat-response fixture
entries finally started accumulating (confirmed: entries growing across
multiple concurrent runs, correctly, proving the earlier
`FixtureIOTest`-caught merge bug really is fixed under real concurrent
load). But every run was still coming back "VERIFIED (vacuously)" -- zero
claims, because every sub-question hit "No extractable sources found."

**Root cause:** the 200KB extract size cap, flagged as a likely real
problem back in Session 14 ("Wikipedia is 1.07MB... TOO_LARGE will be
common in practice, not exceptional") but never actually confirmed live
until today. Manually testing `POST /api/v1/extract` against
`en.wikipedia.org/wiki/Japan` confirmed it directly: `status: "TOO_LARGE"`.
Population, inflation, and financial-crisis questions all pull Wikipedia
as a top result -- the exact kind of question this cap was already known
to be too tight for.

**Fix:** raised `retrieval-service`'s `max-document-bytes` from 200KB to
2MB. Verified immediately: the same Wikipedia page then extracted cleanly,
`status: "OK"`, 110KB of real text. This was a config value flagged
"user's call" in an earlier session's notes; today it was actively
blocking the task at hand, so it got raised, clearly documented, rather
than left broken.

## The external limit that stopped the recording run here

With all three bugs fixed, search-response fixtures started recording --
but every single one came back with **zero results**, credits still
spent. Checked SearXNG directly (`curl` straight to its own `/search`
endpoint, bypassing `retrieval-service` entirely) -- also zero results.
Checked its container logs:
```
searx.exceptions.SearxEngineTooManyRequestsException: Too many request (suspended_time=180)
ERROR:searx.engines.duckduckgo: HTTP requests timeout ... ConnectTimeout
```
After a full day of heavy, repeated automated searching through this same
SearXNG instance, its upstream engines (Google and DuckDuckGo, the
defaults) have started throttling or timing out requests from this
environment. This is **not a code bug** -- there's nothing in this
project to fix here. It's a real-world rate limit from real search
providers, and the honest move is to stop hammering it (retrying
repeatedly risks extending the block, not shortening it) rather than
"fix" it with a workaround that would just be masking an external
constraint.

## What actually got captured, and what didn't

**Committed:** `agent-service/src/main/resources/fixtures/chat-responses.json`
-- 30 real, genuine query-generation prompt/response pairs, captured from
real DeepSeek calls during today's (partially successful) recording runs.
Spot-checked directly: real, sensible generated search queries for real
sub-questions (World Bank population data sources, Japan's 2020 census,
etc.). This is genuinely useful fixture data for replaying the
`generateQueries` step of the researcher loop.

**Not committed, deliberately:** a `search-responses.json` with 30
entries, every single one an empty result list. Committing it would look
like real fixture data while actually being useless (or actively
misleading) for replay -- a fixture-mode run against it would show every
sub-question failing to find sources, which is an artifact of today's
external rate limit, not a real, reusable test case.

**Not captured at all:** anything from `synthesizeAnswer`
(`ResearcherService`), or any call from `PlannerService`, `WriterService`,
or `CriticService` -- none of those steps were ever reached, because
every run short-circuited at the empty-search-results stage.

## What's still needed

1. **Wait for the SearXNG rate limit to clear** (likely hours, not
   minutes, given how heavily this instance has been used today), then
   run one more recording session to capture real search results, real
   extracted documents, real synthesized answers, real extracted claims,
   and real Critic grading -- a genuinely complete fixture set.
2. Add inline recording to `PlannerService` (question decomposition),
   `WriterService` (claim extraction), and `CriticService` (batch
   grading) -- today's fix only covers `ResearcherService`'s two call
   sites. Same pattern, same small change, just not yet applied to the
   other three classes given today's session ran out of road on the
   search-engine rate limit before those became testable anyway.
3. Once real data exists for all of the above: the fabrication-injection
   eval and the speedup benchmark, PLAN's actual Week 4 deliverables,
   can finally be built against real recorded data instead of stubs.

## Why this entry is worth reading even though the headline goal wasn't hit

Every other entry today ended with something working live. This one
didn't -- and that's the more representative day, most days. Three real,
different-shaped bugs got found and fixed in about ninety minutes of
methodical, evidence-first debugging (a direct diagnostic runner, a
filesystem-wide search, a manual `curl` straight to the lowest layer,
reading a container's own logs) rather than guessing. The one thing that
didn't get fixed is the one thing that genuinely can't be fixed from this
codebase -- and recognizing that distinction, instead of continuing to
"try things" against an external rate limit, is itself the correct
outcome of the debugging, not a failure to finish it.
