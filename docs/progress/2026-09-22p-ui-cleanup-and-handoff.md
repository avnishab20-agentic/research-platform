# UI cleanup + stack handoff

## Console cleanup

The Overview tab was a stale, hardcoded dev build-tracker, not a product
UI -- checked visually via Chrome DevTools, not guessed from source. It
showed:
- A "Build progress" checklist naming raw implementation details ("Redis
  Lua", "SET NX PX", "Postgres UPDATE ... RETURNING", "CompletableFuture
  fan-out") with a hardcoded `CAPABILITIES` array frozen at Session 14's
  state -- 6 of its 13 entries marked incomplete (parallel fetch,
  single-flight, `Semaphore(4)`, fan-in, critic loop, evals) even though
  all of them have been done and live-verified for hours.
- `agent-service`/`control-plane` labeled `SKELETON` everywhere, despite
  being the two most-built, most-live-tested parts of the whole project
  at this point.
- A sidebar footer stat ("suite 67/67") shown on every tab, hardcoded,
  never updated, now wrong (the real count is 127+ across four modules).

Removed the `CAPABILITIES` array and its "Build progress" card entirely,
dropped the stale "suite 67/67" stat, and fixed the service-status card
to show all four services as `LIVE` (accurate) with plain, non-jargon
one-line descriptions. Retrieve/Extract/Verify tabs were already clean
on inspection -- no changes needed there.

**Build trap hit again, same one Session 14 already documented:**
`mvn spring-boot:run` serves `target/classes`, not `src/` -- editing
`app.js` and refreshing showed the old page until `retrieval-service`
was actually restarted.

Verified visually, before and after, via Chrome DevTools screenshots --
not just read the diff. Confirmed all 4 services show `LIVE` and the
jargon-heavy checklist is gone.

## One real gap found while testing, left open

Ran a live question through the Verify tab after the UI change (RBI
monetary policy stance). The run completed and reached `VERIFIED`, but
with **zero claims** -- all 8 researchers found nothing, because SearXNG
is still degraded from this session's earlier heavy use (same condition
flagged twice already today). The Verify tab doesn't render any
"no claims found" message in that case -- the step list just ends after
Critic with no report section. This is a real, small UX gap (a vacuous
`VERIFIED` should say so, not go silent), downstream of the same
external SearXNG throttle already named as an open item -- not fixed
here, since fixing it can't be honestly verified against real search
results while SearXNG stays throttled.

## Fabrication eval: real measurement taken

Earlier in this session (see `2026-09-22o`): ran the fabrication-injection
eval against the real DeepSeek-backed critic for the first time. Catch
rate 1.0, false-positive rate 0.5 (fails the <0.10 target) -- reported
honestly in `README.md`, along with the likely methodology confound
(thin single-sentence evidence vs. the real multi-passage RAG context).

## State at handoff

- All 5 Docker containers healthy (postgres, redis, redpanda, searxng,
  extractor).
- All 3 Spring services responding 200 on `/actuator/health`
  (retrieval-service :8081, agent-service :8082, control-plane :8083).
- Full test suite green: `common`+`retrieval-service`+`agent-service`+
  `control-plane`, `mvn test` exit 0.
- Console live at `http://localhost:8081`, cleaned up.
- Everything from this session remains uncommitted, per earlier
  direction to batch the commit.

## Real remaining open items (not UI, not tests -- actual product gaps)

1. Speedup benchmark -- infra ready (`PlannerFixtureStore` built this
   session), data capture still blocked on SearXNG.
2. `search-responses.json`/`extract-responses.json` fixtures still need
   a real recording run once SearXNG clears.
3. Fabrication eval's methodology gap (thin evidence) -- re-run with
   real `retrieveTopK` passages once worth the DeepSeek call cost.
4. Testcontainers integration coverage -- still mock-only for Redis/the
   extractor sidecar.
5. The vacuous-VERIFIED UI gap found above.
6. Week 5 (Kubernetes + KEDA, JWT auth, 3-layer rate limiting) -- a
   separate, deliberately-deferred phase per `docs/PLAN.md`, not part of
   weeks 1-4's scope.
