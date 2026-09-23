-- What the agents did, in their own words, so the browser can show the work
-- instead of only the final claims. Written by control-plane (planner) and
-- agent-service (researcher/writer/critic); read by RunController's SSE poll.
-- A Postgres table rather than the agent.events Kafka topic: the SSE stream
-- already polls Postgres, and a table keeps the trail for a page reload.
CREATE TABLE run_events (
    id         BIGSERIAL PRIMARY KEY,
    run_id     UUID NOT NULL REFERENCES runs(id),
    node_id    UUID REFERENCES dag_nodes(id),
    agent      VARCHAR(20) NOT NULL,
    message    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_run_events_run_id ON run_events(run_id, id);

-- The Critic's correction round (PLAN: "re-research the failed claims ONLY").
-- original_text keeps what the Writer first said when a claim is rewritten;
-- correction is NULL (untouched), REVISED, or REMOVED.
ALTER TABLE claims ADD COLUMN original_text TEXT;
ALTER TABLE claims ADD COLUMN correction VARCHAR(20);
