-- PLAN's run-level ceiling (guardrail #4): search/LLM-call counters per
-- run, so a runaway planner or researcher can't burn an unbounded number
-- of calls. Same atomic-counter pattern as dag_levels' fan-in --
-- UPDATE ... RETURNING under Postgres row locking, not an in-memory
-- counter (CLAUDE.md: "must NOT live in a ConcurrentHashMap or
-- AtomicInteger" -- that breaks restart-survival, and the same reasoning
-- applies here: agent-service can restart mid-run without losing count).

CREATE TABLE run_usage (
    run_id     UUID PRIMARY KEY REFERENCES runs(id),
    searches   INT NOT NULL DEFAULT 0,
    llm_calls  INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
