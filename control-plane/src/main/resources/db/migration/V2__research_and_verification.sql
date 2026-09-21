-- Week 2 (sources) and Week 3 (claims, claim_verdicts) tables, plus the
-- pgvector table the RAG chunk/embed/retrieve utility reads and writes.
-- Flyway-first per CLAUDE.md: this schema is created here, not by
-- Hibernate ddl-auto or Spring AI's own schema auto-init (both disabled).

CREATE TABLE sources (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         UUID NOT NULL REFERENCES runs(id),
    url            TEXT NOT NULL,
    tier           INT NOT NULL,
    extracted_text TEXT,
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, url)
);
CREATE INDEX idx_sources_run_id ON sources(run_id);

CREATE TABLE claims (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES runs(id),
    node_id         UUID REFERENCES dag_nodes(id),
    section_heading TEXT NOT NULL,
    text            TEXT NOT NULL,
    source_id       UUID NOT NULL REFERENCES sources(id),
    kind            VARCHAR(20) NOT NULL,
    corroborating   UUID[] NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_claims_run_id ON claims(run_id);
CREATE INDEX idx_claims_source_id ON claims(source_id);

CREATE TABLE claim_verdicts (
    claim_id         UUID PRIMARY KEY REFERENCES claims(id),
    verdict          VARCHAR(20) NOT NULL,
    evidence_passage TEXT,
    graded_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Spring AI's PgVectorStore schema, hand-written so it goes through Flyway
-- instead of the library's own ddl-auto-style schema init (kept disabled --
-- see application.yml). 384 = the output size of the local all-MiniLM-L6-v2
-- embedding model (spring-ai-starter-model-transformers); if that model ever
-- changes, this dimension has to change with it.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(384)
);
CREATE INDEX ON vector_store USING HNSW (embedding vector_cosine_ops);
