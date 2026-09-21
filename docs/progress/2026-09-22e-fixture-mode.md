# 2026-09-22 (part 5) — fixture mode, and a bug caught by its own test

Week 4 begins. PLAN calls fixture mode out specifically: *"All three evals
depend on this — an eval you can't afford to run is an eval you don't
have."* Building it before the eval logic itself, matching that order.

## What fixture mode actually is here

Two problems, same shape: `ResearcherService`/`CriticService` need real
search results and real LLM answers to do anything useful, but every real
run costs money and depends on the network being up. For the Week 4 evals
(fabrication-injection catch rate, the speedup benchmark) to run for free,
repeatably, on demand — the same question has to produce the exact same
search results and the exact same model answer every single time it's
asked, with zero network calls.

**The mechanism: record once from a real run, replay forever after, for
free.**

### The pieces

**`RetrievalClient` became an interface**, not a rename with extra steps
-- this was the cleanest possible refactor: `ResearcherService` and
`CriticService` already only ever referred to it by that name, so turning
the class into an interface and adding a second implementation touched
zero calling code.

- **`HttpRetrievalClient`** -- the real one (what used to be the whole
  class), active whenever the app isn't running under the `fixture`
  profile.
- **`FixtureRetrievalClient`** -- active under `fixture`, reads a canned
  `SearchResponse`/`ExtractedDocument` straight from a JSON file instead
  of calling `retrieval-service` at all.

**The chat side needed the same split**, but `ChatModel` is Spring AI's
own autoconfigured bean, not something this project owns outright --
different shape of fix:

- **`FixtureChatModel`** -- a from-scratch `ChatModel` implementation,
  active under `fixture`, that hashes the exact prompt text (every
  message concatenated) and looks up a recorded response for that exact
  hash.
- **`RecordingChatModel`** -- a decorator around the *real* autoconfigured
  `ChatModel`, registered via `@Primary` + `@ConditionalOnProperty` so it
  only exists at all when `fixtures.record-mode: true` is set. It calls
  the real DeepSeek model, records the response on the way out, and
  returns it unmodified -- recording never changes what the caller
  actually sees.

**`FixtureIO`** is the one piece of storage logic both sides share: one
JSON file per fixture type (`search-responses.json`,
`extract-responses.json`, `chat-responses.json`), each a flat
key -> value map. Keys are a 16-character SHA-256 prefix of whatever was
actually asked (the joined query list, a URL, or the full prompt text) --
same approach `retrieval-service`'s own cache keys already use, and for
the same reason: an exact match on real content, not a fuzzy one, and no
awkward characters landing in a JSON key.

**A miss is loud, on purpose.** `FixtureMissException` is thrown, not an
empty result returned, when fixture-profile replay has no recorded answer
for something. An empty `SearchResponse` standing in for "we forgot to
record this" would look exactly like a legitimate "no results found" --
the eval would silently grade against the wrong thing. The exception
message even says what to do about it: run once with recording on against
the real services, commit the new fixture file.

## The recording workflow, end to end

1. Set `fixtures.record-mode: true`, run the app normally (real Docker,
   real DeepSeek key, real credits spent -- recording is not free, it's a
   one-time cost to make everything *after* it free).
2. Submit real questions through the normal pipeline.
3. Every search, every extract, every LLM call gets written to
   `agent-service/src/main/resources/fixtures/*.json` as a side effect,
   automatically, with zero fixtures hand-typed.
4. Commit the fixture files.
5. From then on, `spring.profiles.active` including `fixture` replays
   everything captured, for $0, with no network dependency at all --
   which is also, per PLAN, how the Week 4 SSE page gets built without
   burning credits on every UI tweak.

## A real bug, caught by the test written for this exact purpose

`FixtureIOTest.recordWritesValidJsonToTheConfiguredDirectory` recorded two
keys in sequence and asserted both were in the file afterward. It failed:
only the second key was there.

**Root cause:** `record()`'s read-merge-write logic was merging against
`loadRaw()` -- which reads from the *classpath* copy of the fixture file
(the one baked in at the last build), not the file actually being written
to on disk. During a real recording session capturing many entries in one
run, the classpath copy never changes mid-run, so every `record()` call
after the very first would "merge" against a stale, empty-or-outdated
view and overwrite the real file with just that one new key -- silently
destroying everything recorded earlier in the same session. A recording
run meant to capture, say, 40 search/extract/chat pairs across 8
sub-questions would have ended with a fixture file containing exactly
one entry: whichever happened to be recorded last.

This is precisely the value of writing the test immediately, before ever
trusting the mechanism against real data: the bug would have been
completely invisible during development (each individual `record()` call
succeeds, logs cleanly, the file exists and contains valid JSON -- it's
just missing everything but the newest entry), and would only have
surfaced the first time someone tried to replay a fixture set with more
than one recorded item and found most of it silently gone.

**Fix:** added a second, disk-reading load path (`loadFromDisk`) used only
by `record()`, separate from the classpath-reading `loadRaw()` used by
`read()`. The two methods now correctly serve two different purposes:
`read()` replays what's on the classpath (the committed, built version);
`record()` merges against what's actually on disk right now (the
in-progress recording session).

## Tests written (18 total, all mocked or file-based -- zero Docker needed)

- **`FixtureIOTest`** (6): `keyFor()` stability and uniqueness, a real
  read against a committed test fixture, the loud-miss behavior for both
  a missing key and a missing file entirely, and the record/merge
  round-trip that caught the bug above.
- Existing `ResearcherServiceTest`/`CriticServiceTest` (12, from the
  previous cycle) re-run clean -- confirms the `RetrievalClient`
  interface split didn't disturb anything already passing.

## What's not done yet

**No real fixture data exists yet.** Everything above is infrastructure,
compile-and-unit-test verified, never run against a real Docker stack.
The next live cycle needs one deliberate recording run (`fixtures.record-mode:
true`, a handful of real questions through the full pipeline) to actually
populate `search-responses.json`/`extract-responses.json`/
`chat-responses.json` with real content -- nothing to replay yet.

**The eval harness itself isn't built.** Fixture mode is the foundation
PLAN says to build first; the fabrication-injection eval (corrupt claims,
re-run the Critic under fixture mode, measure catch rate) and the
speedup benchmark (concurrency 1 vs 6, fixture mode, ~20 questions) both
still need writing on top of this.

## Next

Bring Docker up, run one recording session against a handful of real
questions to populate the fixture files for real, commit them, then build
the fabrication-injection eval and the speedup benchmark on top of
working fixture data.
