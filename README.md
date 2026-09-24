# Research Platform

[![backend-pr-validation](https://github.com/avnishab20-agentic/research-platform/actions/workflows/backend-pr-validation.yml/badge.svg)](https://github.com/avnishab20-agentic/research-platform/actions/workflows/backend-pr-validation.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=avnishab20-agentic_research-platform&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=avnishab20-agentic_research-platform)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=avnishab20-agentic_research-platform&metric=coverage)](https://sonarcloud.io/summary/new_code?id=avnishab20-agentic_research-platform)

You ask a question. A team of AI agents splits it into smaller questions,
researches each one on the live web **at the same time**, and writes an answer.
Then a separate **fact-checker agent re-opens every source and checks every
sentence** against it before you see the result.

That last step is the point of the project. Most AI research tools give you
an answer with links. This one gives you an answer where every statement has
been checked, and shows you the exact sentence from the source that it was
checked against, so you can verify it yourself.

**Live:** https://avnish-research.southindia.cloudapp.azure.com (runs on Azure;
capped at 20 questions a day to keep costs down).

---

## How one question gets answered

```
 You: "What is the RBI's current repo rate policy?"
  │
  ▼
 1. PLANNER      splits it into 5 independent sub-questions
  │
  ▼
 2. RESEARCHERS  5 of them work in parallel, one per sub-question:
  │              search the web → read the top pages → pick the most
  │              relevant passages → write a short answer from them only
  ▼
 3. WRITER       breaks every answer into short, single-fact statements
  │              ("claims"), each tied to exactly ONE source
  ▼
 4. FACT-CHECKER re-fetches every source and grades every claim:
  │              SUPPORTED · PARTIAL · UNSUPPORTED · CONTRADICTED · UNREACHABLE
  │              A failed claim is researched again and rewritten, or removed.
  ▼
 5. RESULT       a short conclusion built ONLY from claims that passed,
                 plus every claim with its evidence passage and source
```

Each run ends as **VERIFIED**, **UNVERIFIED** (too many claims failed) or
**PARTIAL** (a limit was hit). A bad result is still shown, clearly labelled;
it is never hidden, because hiding it would hide exactly what this project is
built to expose.

## The pieces

Three Spring Boot services, plus two small helper containers:

| Part | Port | What it does |
|---|---|---|
| [`control-plane`](control-plane/) | 8083 | The front desk. Takes your question, runs the planner, hands out the sub-questions, notices when all of them are done, and streams live progress to the browser. |
| [`agent-service`](agent-service/) | 8082 | The AI workers: RESEARCHER, WRITER and FACT-CHECKER (called CRITIC in the code). One app with three roles. |
| [`retrieval-service`](retrieval-service/) | 8081 | The only part allowed to touch the web. Searches, downloads pages, caches results and enforces rate limits. Also serves the web page you use. |
| [`common`](common/) | — | Shared data shapes (Java records) that the services send to each other. Not an app. |
| `extractor` | 8000 | A small Python service that turns a web page's HTML into clean article text. |
| `searxng` | 8080 | A self-hosted search engine: free, no API key needed. |

**How they talk:** the slow, multi-step work (planning → researching → writing →
checking) passes messages through **Kafka**, so no work is lost if a service
crashes halfway. Quick lookups (search this, fetch that page) are plain **HTTP**
calls to `retrieval-service`.

**Storage:** **Postgres** holds runs, claims, verdicts and the progress counters,
plus page passages as vectors (with the pgvector extension) for finding relevant
text. **Redis** caches search results and pages and runs the rate limiters.

For the full picture, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Run it on your laptop

You need Java 21, Maven, Docker, and a DeepSeek API key.

```bash
cp .env.example .env              # then put your DEEPSEEK_API_KEY in .env
docker compose up -d              # Postgres, Redis, Redpanda (Kafka), SearXNG, extractor

export DEEPSEEK_API_KEY=...       # the Spring apps read it from the shell
mvn -pl retrieval-service spring-boot:run &
mvn -pl agent-service spring-boot:run &
mvn -pl control-plane spring-boot:run &
```

Open **http://localhost:8081** and ask a question. Or use the API:

```bash
curl -X POST http://localhost:8083/api/v1/runs \
  -H "Content-Type: application/json" \
  -d '{"question":"What is the current RBI repo rate policy?"}'
# → {"runId":"..."}; then watch it live:
curl -N http://localhost:8083/api/v1/runs/<runId>/events
```

## Tests and quality checks

```bash
mvn verify                        # compile, run all tests, write coverage reports
```

Every pull request runs two automatic checks, and **both must pass before
merging** into `main`:

- **`maven`**: builds all modules together and runs every test.
- **SonarQube Cloud Quality Gate**: no new bugs or security issues, and at
  least 80% of changed lines covered by tests.

The tests never call a real AI model or the real web. They use a fake chat model
that returns pre-written replies, so they are fast, free and give the same
result every time.

## Limits (guardrails)

Every limit is a setting in a service's `application.yml`, never a number
hard-coded in Java, so changing one is a config edit and a restart. Most sit in
a shared `guardrails:` block (bound to one Java record in `common`); the
planner's limits sit under `planner:` in control-plane. Examples: at most 5
sub-questions, 40 searches and 35 AI calls per run; 10 minutes per run;
1,000 searches a day; 1 request per second to any one website; pages over
2 MB are skipped; URLs pointing into private networks are blocked.

One switch controls all of them:

```yaml
guardrails:
  mode: ENFORCE   # ENFORCE = stop when a limit is hit · SHADOW = log it and carry on
```

## Evals (does the fact-checker actually work?)

1. **Fabrication test:** takes real claims, deliberately breaks some of them
   (changes a number, flips "rose" to "fell", swaps a name, invents a claim), and
   checks whether the fact-checker catches them. It has to catch the broken ones
   *without* flagging the good ones. Measured once against the real model: it
   caught **100%** of broken claims (target: over 85%), but also wrongly flagged
   **50%** of good ones (target: under 10%). The likely cause is known and is
   documented in [the measurement log](docs/progress/2026-09-22o-fabrication-eval-live-measurement.md).
   Not fixed yet.
2. **Guardrail tests:** one test per limit, proving it really stops things. All
   six pass.
3. **Speed test** (1 researcher vs 6): not measured yet. Recording it needs
   repeatable search results, and the free search engine rate-limits repeated
   automated use.

## Proven: a crash doesn't lose work

The progress counter lives in Postgres, not in a Java variable. To prove that
matters, a run was started and `agent-service` was killed with `kill -9` before
any researcher had started. On restart, Kafka handed the 8 unfinished
sub-questions to the new process, and the run finished **VERIFIED**, with no
duplicates and no stuck state. Details:
[restart-survival log](docs/progress/2026-09-22l-restart-survival-demo.md).

## Deployment

Merging to `main` automatically tests, builds, deploys and health-checks each
service on **Azure Kubernetes Service**. See
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for how the pipeline works and how the
Azure resources are set up.

## More docs

| Doc | Read it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How all the parts fit together, the database tables, and the key design decisions |
| [docs/KAFKA.md](docs/KAFKA.md) | Kafka from scratch, following one message through the code |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | CI/CD pipeline, pull request checks, Azure setup |
| [docs/SECRETS.md](docs/SECRETS.md) | Where every password and API key lives |
| [docs/LEARNING.md](docs/LEARNING.md) | What each hand-built piece teaches, as interview answers |
| [docs/PLAN.md](docs/PLAN.md) | The original build plan, week by week |
| [docs/progress/](docs/progress/) | Dated logs of what was built and the bugs found along the way |

## Deliberately not built

Sub-questions that depend on each other (all run independently), a paid search
API as the default, user accounts and login, and a rich front-end framework.
Each was left out on purpose to keep the project focused on verification.
