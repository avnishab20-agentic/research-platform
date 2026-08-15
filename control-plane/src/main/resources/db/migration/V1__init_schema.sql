CREATE TABLE runs (
                      id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                      question    TEXT NOT NULL,
                      status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                      report      JSONB,
                      created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                      updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dag_nodes (
                           id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           run_id        UUID NOT NULL REFERENCES runs(id),
                           level         INT NOT NULL DEFAULT 0,
                           depends_on    UUID REFERENCES dag_nodes(id),
                           sub_question  TEXT NOT NULL,
                           status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                           finding       JSONB,
                           created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                           updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dag_nodes_run_id ON dag_nodes(run_id);

CREATE TABLE dag_levels (
                            run_id      UUID NOT NULL REFERENCES runs(id),
                            level       INT NOT NULL,
                            expected    INT NOT NULL,
                            completed   INT NOT NULL DEFAULT 0,
                            deadline    TIMESTAMPTZ NOT NULL,
                            PRIMARY KEY (run_id, level)
);

CREATE INDEX idx_dag_levels_run_id ON dag_levels(run_id);


