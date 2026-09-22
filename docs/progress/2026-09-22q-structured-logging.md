# Structured logging + correlation IDs, all 3 services

## What was asked

"Add good logs everywhere so I can use something [later] -- will decide
later what logging application to use." Not a specific tool yet, so the
goal was to make the logs themselves ready for whatever gets picked,
rather than guess the tool and build for it.

## What was built

**JSON log output, switchable by Spring profile.** Added
`net.logstash.logback:logstash-logback-encoder` to all three services
and a `logback-spring.xml` each: plain, human-readable text by default
(unchanged local dev experience), JSON to stdout when `SPRING_PROFILES_ACTIVE`
includes `json`. JSON was picked specifically because every log
aggregator worth considering (ELK, Loki, CloudWatch, Datadog, Splunk)
ingests plain JSON lines with zero per-tool parsing config -- the choice
of *tool* stays open, the *log shape* doesn't need revisiting later. This
also lines up with Week 5's Kubernetes move: JSON-on-stdout is exactly
what a k8s log shipper (Fluent Bit, Promtail) expects to tail.

**A `runId` correlation ID threaded through all three services.** Set via
`MDC.put("runId", ...)` at every point a class first receives a run's id,
cleared in a `finally` so it never leaks onto an unrelated request or
message on a reused thread:
- `control-plane`: `PlannerService.submit()` (from the moment the id is
  generated), `RunController`'s three `{id}`-scoped endpoints
  (`status`/`events`/`report`), `FindingListener.onFinding()`.
- `agent-service`: all three `@KafkaListener` methods
  (`ResearchSubtaskListener`, `RunReadyListener`, `ClaimsReadyListener`).

Verified live, not just read back: submitted a real run, grepped both
`control-plane`'s and `agent-service`'s logs for that run's UUID, and
confirmed the exact same id shows up in both processes' log lines even
though they're separate JVMs -- which is the entire point. Once a log
aggregator exists, filtering on `runId` will show one run's complete
story across all three services in one view.

## One real bug caught and fixed before it shipped

`RunController.report()` calls the public `status(id)` method internally
to reuse its query. If both had their own `MDC.put`/`try`/`finally
MDC.remove`, the inner call's `finally` would clear the correlation id
partway through `report()`'s own remaining work (the claims query) --
every log line after that point in a `report()` call would have silently
lost its `runId`. Fixed by extracting a private, MDC-free `loadStatus(id)`
helper that both the public `status()` endpoint and `report()`/`buildReport()`
call, so only the outermost method per request owns the MDC lifecycle.
Same fix applied to `report()` itself (renamed its body to `buildReport()`).

`events()`'s SSE poller runs on its own dedicated background thread, not
the request thread that called `events()` -- MDC is thread-local, so the
`MDC.put`/`remove` had to go inside the scheduled poll tick itself, not
just at the top of `events()`.

## Trap hit along the way

XML comments can't contain `--` anywhere inside them, not even as a
sentence-dash. First draft of every `logback-spring.xml` (and the pom.xml
dependency comments) used "tool-agnostic -- any log shipper..."-style
prose throughout, which broke Logback's own XML parser at startup
(`SAXParseException: The string "--" is not permitted within comments`).
A blind `sed 's/--/:/g'` fix made it worse -- it also mangled the `<!--`
and `-->` comment delimiters themselves into `<!:`/`:>`, breaking the XML
structurally rather than just the prose. Fixed by rewriting all three
`logback-spring.xml` files cleanly with single `-` in place of `--`
throughout, delimiters intact.

## Also fixed along the way (unrelated to logging)

Docker Desktop had stopped (machine likely slept) partway through this
work, surfacing as `control-plane`'s `ControlPlaneApplicationTests`
failing with `Connection to localhost:5432 refused`. Not a code issue --
`open -a Docker`, waited for the daemon, `docker compose up -d`, waited
for Postgres to report ready, re-ran the suite clean.

## Verified

- `common`+`retrieval-service`+`agent-service`+`control-plane`: `mvn test`
  green, 127 run / 1 skipped (the disabled live fabrication eval).
- Live plain-text output confirmed: `runId=<uuid>` appears on every log
  line during a real run, empty when outside one.
- Live JSON output confirmed (`retrieval-service` restarted with
  `-Dspring-boot.run.profiles=json`): clean JSON lines,
  `"service":"retrieval-service"` tag present on every line.
- Cross-service correlation confirmed: same `runId` in both
  `control-plane`'s and `agent-service`'s logs for one real run.
- All three services restarted back to normal (plain-text) mode after
  testing.

## Not done

- Only `runId` is threaded through as a correlation id. `retrieval-service`
  has no `runId` concept of its own (it's a shared quota boundary hit by
  many runs concurrently) -- it gets the JSON output shape but no
  per-request correlation id of its own. Adding one (a generic request id
  via a Servlet filter) would be a small, separate follow-up if per-request
  tracing inside retrieval-service specifically becomes useful later.
- No log shipper/aggregator is actually wired up yet -- that's explicitly
  the "decide later" part. The `json` profile just makes the output ready
  for whichever one gets chosen.
