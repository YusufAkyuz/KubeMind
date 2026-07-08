-- RAG (Phase A3): the vector store lives in the same Postgres already in the
-- stack — no extra service to operate, matching the self-hosted deploy story.
-- Schema mirrors exactly what Spring AI's PgVectorStore expects (id-type=uuid,
-- dimensions=768 for the nomic-embed-text embedding model) — created here via
-- Flyway rather than PgVectorStore's own initialize-schema, per this project's
-- "every schema change ships as a migration" rule.
--
-- PREREQUISITE (one-time, run by a DB superuser before first boot — the app's
-- own role deliberately isn't a superuser, and CREATE EXTENSION requires one):
--   CREATE EXTENSION IF NOT EXISTS vector;
--   CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
--   GRANT USAGE ON SCHEMA public TO kubemind;

CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(768)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON vector_store
    USING hnsw (embedding vector_cosine_ops);

-- Admin-authored runbooks (RAG corpus 2, tenant-scoped) — the vector_store row
-- (same id, embedding only) holds the searchable side; this table is the
-- source of truth for listing/editing/deleting in the UI.
CREATE TABLE runbooks (
    id          UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    cluster_id  BIGINT        NOT NULL,
    title       VARCHAR(255)  NOT NULL,
    content     TEXT          NOT NULL,
    created_by  VARCHAR(255)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_runbooks_cluster ON runbooks (cluster_id);
