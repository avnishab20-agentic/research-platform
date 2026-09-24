# control-plane

**Port 8083.** The front desk. It takes your question, splits it into
sub-questions, hands them out, notices when they're all answered, and streams
live progress to the browser. It also owns the database schema: every Flyway
migration lives here.

## Endpoints

| Endpoint | Does |
|---|---|
| `POST /api/v1/runs` | start a run: `{"question": "..."}` → `{"runId": "..."}`. Returns **429** once the daily limit (20 runs) is reached |
| `GET /api/v1/runs/{id}` | the run's question and status |
| `GET /api/v1/runs/{id}/events` | live progress as SSE (Server-Sent Events: one HTTP connection the server keeps open and writes updates into) |
| `GET /api/v1/runs/{id}/report` | the finished report: conclusion, every claim, its verdict, evidence sentence and source |

## How it works

**Planner** (`planner/PlannerService`). One DeepSeek call splits the question
into 5 sub-questions that can each be answered on their own. It saves them and
publishes one Kafka message per sub-question on `research.subtasks`. Each
message is keyed by the sub-question's own id, which spreads them across
partitions so they're researched in parallel.

**Fan-in** (`fanin/FanInService`). Each finished answer runs one SQL statement:

```sql
UPDATE dag_levels SET completed = completed + 1
WHERE run_id = ? AND level = 0
RETURNING completed, expected
```

Postgres applies these one at a time for the same row, so exactly one answer
sees `completed == expected`, and that one tells the writer to start
(`run.ready`). The count lives in the database, not in Java memory, so a
restart doesn't lose it.

**Deadline sweeper** (`fanin/DeadlineSweeper`). Every 15 seconds, a run whose
3-minute deadline has passed is marked `PARTIAL` and handed to the writer with
whatever answers did arrive. One stuck researcher can't block a run forever.

**Live progress** (`web/RunController`). A shared scheduler checks Postgres
once a second for each open stream and sends only what changed: new activity
lines, and progress such as "3 of 5 done". The stream closes when the run
finishes.

## Database migrations

`src/main/resources/db/migration/V1…V5`. Flyway runs them on startup, and
Hibernate only checks that the tables match (`ddl-auto: validate`). To change
the schema, add a new `V6__…sql` file. Never edit one that has already run.
The tables are explained in [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md#database-tables-postgres).

## Key settings (`application.yml`)

| Block | Controls |
|---|---|
| `planner` | sub-questions per run (`min-fan-out`, `max-fan-out`), runs per day, fan-in deadline |
| `spring.kafka` | see [docs/KAFKA.md](../docs/KAFKA.md) |
| `spring.datasource` | database; the password comes from `SPRING_DATASOURCE_PASSWORD` |

## Why the counter is in Postgres

A Java counter (`AtomicInteger`, `ConcurrentHashMap`) is simpler, until the
service restarts (the count is gone and the run waits forever) or you run two
copies (each has its own count). The database row gets both right: exactly one
trigger, and it survives restarts. This was proven by killing
`agent-service` in the middle of a run; see
[the restart-survival log](../docs/progress/2026-09-22l-restart-survival-demo.md).

## Run and test

```bash
mvn -pl control-plane -am test -Dtest='!ControlPlaneApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl control-plane spring-boot:run   # needs Postgres, Kafka, DEEPSEEK_API_KEY
```

`ControlPlaneApplicationTests` starts the whole app, so it needs a running
Postgres (`docker compose up -d`). The other tests need nothing.
