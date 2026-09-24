# common

Shared data shapes: plain Java `record`s that the three services send to each
other. It's a library, not an app: no `main` method, nothing here runs on its
own.

## What's in it

| Type | What it is |
|---|---|
| `ResearchSubtask` | one sub-question to research (Kafka: planner → researcher) |
| `ResearchFinding` | a researcher's answer with its sources and confidence (Kafka: researcher → fan-in) |
| `RunReady`, `ClaimsReady` | "this run is ready for the next step" signals (Kafka) |
| `Claim`, `ClaimKind`, `SourceRef`, `Section`, `Report` | the parts of a written report |
| `Verdict`, `ClaimVerdict` | the fact-checker's grade for one claim |
| `AgentEvent` | a progress event (defined, not used yet) |
| `KafkaTopics` | the topic names, as constants |
| `GuardrailProperties`, `GuardrailMode` | the Java record that the `guardrails:` config block is loaded into |

## Why a shared module

Every service has to agree on what a message looks like: the same fields, the
same types. If each service defined its own copy, a mismatch (say, `String` in
one and `UUID` in another) would only show up when a real message failed to
load at runtime. With one shared copy, the same mistake becomes a **compile
error**.

## Rules for this module

- **Data shapes only.** No business logic, no database or Kafka code. If
  something here needs a `@Service` or a `JdbcTemplate`, it belongs in a
  service instead. The one exception is `GuardrailProperties`, which carries a
  Spring `@ConfigurationProperties` annotation so every service can load the
  same config block.
- **Never add `spring-boot-maven-plugin` here.** It turns a module into a
  runnable "fat jar", which breaks it as a library the other modules depend on.
