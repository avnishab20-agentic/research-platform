# 2026-09-22 (part 8) — the fabrication-injection eval, ready and tested

Next item, fully done before moving on: PLAN's fabrication-injection
eval, Week 4's actual differentiator. Built the complete harness --
corruption logic, the eval loop, catch-rate/false-positive-rate math,
threshold checking -- and proved it correct with a fully scripted grader,
being explicit about what that does and doesn't prove.

## The corruption logic (`ClaimCorruptor`)

PLAN's five types, applied "programmatically" -- no LLM call, so the
eval this feeds into stays free and deterministic, matching CLAUDE.md's
"evals run on fixture mode and cost $0."

- **SWAP_NUMBER** -- finds the first number in a claim (handles `$`,
  `%`, commas, decimals) and scales it by 1.8x. A fixed offset was
  considered and rejected: `+1` on `"4.2%"` rounds away to nothing after
  formatting, silently producing a "corrupted" claim identical to the
  original.
- **INVERT** -- a small dictionary of polarity-word pairs
  (grew/declined, rose/fell, increased/decreased, higher/lower...),
  whole-word case-insensitive match, first occurrence swapped.
- **SWAP_ENTITY** -- finds the *longest* run of capitalized words (a
  crude but effective proxy for "the actual named entity" in a short
  claim sentence -- "Reserve Bank of India" beats any shorter
  incidental capital) and replaces it with one of four generic
  alternate entity names.
- **OVERREACH** -- PLAN calls this "the hardest and most realistic
  category," and it's handled the same way: a hedge-to-absolute word
  map (`some` -> `all`, `some analysts` -> `analysts unanimously`,
  `likely` -> `certainly`...).
- **FABRICATE** -- not a mutation of existing text. Inserts a wholly
  new, deliberately generic, deliberately unverifiable claim that names
  no real fact from the source, citing the real source URL PLAN asks
  for ("insert plausible claim citing a real source").

**Every method returns `Optional<String>` empty, not the unchanged
text, when a corruption type genuinely doesn't apply** (no number in
the claim, no polarity word, no proper noun). This matters more than it
looks: silently returning unchanged text would let the eval think a
claim was corrupted when it wasn't, quietly inflating the measured
catch rate for nothing.

**12 tests**, one pair (applies / doesn't apply) per corruption type.

## The eval harness (`FabricationEval`)

`ClaimGrader` is one interface, one method:
`Verdict grade(String claimText, List<String> evidencePassages)`. This
is the seam: `CriticService`'s real grading (live DeepSeek, or its
fixture-mode replay once real recorded data exists) implements it for a
genuine measurement; a scripted stub implements it for testing the
harness itself.

`FabricationEval.run(knownGoodClaims, corruptCount, grader)`: corrupts
the first `corruptCount` claims (cycling through all five types),
grades every corrupted claim and every remaining clean claim through
the given grader, and returns a `FabricationEvalResult` with both of
PLAN's numbers -- `catchRate` (corrupted claims *not* graded SUPPORTED,
over corrupted count) and `falsePositiveRate` (clean claims incorrectly
flagged, over clean count) -- plus a `passes(minCatchRate,
maxFalsePositiveRate)` check against the `guardrails.eval` thresholds
already in the config tree (0.85 / 0.10).

A corruption that didn't actually apply (a claim with no number, asked
for SWAP_NUMBER) is **excluded from the corrupted count entirely**, not
counted as a miss -- same "don't inflate the measurement" reasoning as
`ClaimCorruptor`'s own `Optional.empty()`.

## What the tests prove, and what they deliberately don't

**`FabricationEvalTest`** (5 tests) uses a fully scripted grader -- a
pre-set sequence of verdicts, not a simulation of real model behavior.
This proves the harness's own bookkeeping is correct: the right claims
get corrupted, the right ones get graded, the ratios compute exactly
right (hand-verified: 4 of 5 caught = 0.8 catch rate, 0 of 3 flagged =
0.0 false-positive rate), and the pass/fail threshold check works in
both directions -- including a test that deliberately shows *catching
everything is not automatically a pass* ("a grader that flags
everything" scores a perfect catch rate and a 100% false-positive rate,
correctly fails `passes()`). This mirrors PLAN's own warning almost
word for word: *"a Critic that flags everything scores a perfect catch
rate and is useless."*

**What this does not prove:** whether DeepSeek, grading through the
real `CriticService`, actually clears the 0.85/0.10 bar on real claims.
That needs `CriticService`'s real grading wired in as a `ClaimGrader`
implementation and run against real data -- both still blocked on the
same thing today's earlier entry named: the SearXNG rate limit, which
stands between here and a real recording session.

## Tests, full picture

`agent-service`: 40/40 green across all mocked/file-based tests
(`RunUsageGuardTest`, `CriticServiceTest`, `FixtureIOTest`,
`ClaimCorruptorTest`, `FabricationEvalTest`, `ResearcherServiceTest`).
Zero Docker needed for any of it.

## Next

A `CriticService`-backed `ClaimGrader` adapter is the piece standing
between this harness and a real measurement -- not built this pass,
named directly rather than implied as done. `CriticService`'s real
grading is currently batched and tightly coupled to `ClaimRow`/Postgres
persistence inside `verify()`; extracting a clean single-claim grading
seam is real, considered work, not a quick wire-up, and doesn't block
anything else being built right now.

Next item to build fully: the guardrail tripwire eval (PLAN's second
eval -- six cases asserting each guardrail actually fires when it
should). Same shape as this one: buildable and testable today without
live services, since it's asserting on the guardrail *decision logic*
(`QuotaService.checkBudget()`, `RunUsageGuard.trySearch()`/
`tryLlmCall()`) that's already built and already has its own direct
unit tests -- the tripwire eval's job is to prove those decisions fire
correctly as a *set*, the way a config refactor could accidentally
break one guardrail while leaving the others looking fine.
