# Progress logs

Dated notes written while the project was built: what was done, what broke,
and how it was fixed. They are a history, so they describe the code **as it
was on that day**. For how things work now, read
[ARCHITECTURE.md](../ARCHITECTURE.md).

They're most useful as real stories: a bug that was actually hit, and why the
fix is what it is.

| Log | What happened |
|---|---|
| [2026-09-21](2026-09-21-week1-close-and-week2-start.md) | Week 1 finished; RAG added to the plan |
| [2026-09-21b](2026-09-21b-kafka-and-schema.md) | Kafka wiring and the Week 2–3 database tables; a library version bug |
| [2026-09-21c](2026-09-21c-deepseek-swap.md) | Switched the AI model from Claude to DeepSeek, to cut cost |
| [2026-09-22](2026-09-22-researcher-loop-live.md) | The researcher works end to end against the real web |
| [2026-09-22b](2026-09-22b-planner-fanin-live.md) | Planner and fan-in working; research really runs in parallel |
| [2026-09-22c](2026-09-22c-critic-built.md) | The fact-checker (critic) built |
| [2026-09-22d](2026-09-22d-full-pipeline-verified.md) | Full pipeline runs live: research → write → check → VERIFIED |
| [2026-09-22e](2026-09-22e-fixture-mode.md) | Fixture mode (record and replay), and a bug its own test caught |
| [2026-09-22f](2026-09-22f-recording-run-three-bugs.md) | Recording real runs: three bugs and one outside limit |
| [2026-09-22g](2026-09-22g-run-level-ceiling.md) | Per-run limits on searches and AI calls |
| [2026-09-22h](2026-09-22h-fabrication-eval.md) | The fabrication eval: break claims on purpose, check they're caught |
| [2026-09-22i](2026-09-22i-ssrf-private-network-block.md) | Blocking URLs that point into private networks (SSRF) |
| [2026-09-22j](2026-09-22j-guardrail-tripwire-eval.md) | One test per guardrail, all six covered |
| [2026-09-22k](2026-09-22k-sse-page-live-verified.md) | The live progress page checked against a real run |
| [2026-09-22l](2026-09-22l-restart-survival-demo.md) | **Restart-survival demo:** killed a service mid-run; the run still finished |
| [2026-09-22m](2026-09-22m-single-flight-verified-and-scheme-allowlist.md) | 20 identical searches → 1 real search; URL scheme allow-list |
| [2026-09-22n](2026-09-22n-planner-fixture-infra-and-readme.md) | Record and replay for the planner; first root README |
| [2026-09-22o](2026-09-22o-fabrication-eval-live-measurement.md) | First real fabrication-eval score: catch rate 1.0, false positives 0.5 |
| [2026-09-22p](2026-09-22p-ui-cleanup-and-handoff.md) | Web page clean-up |
| [2026-09-22q](2026-09-22q-structured-logging.md) | JSON logs, with a run id on every line across all services |
