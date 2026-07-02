CREATE TABLE ai_diagnoses (
    id            BIGSERIAL     PRIMARY KEY,
    -- Single-cluster for now; column exists so multi-cluster (Phase 5) is additive.
    cluster_id    BIGINT,
    resource_kind VARCHAR(64)   NOT NULL,
    resource_ns   VARCHAR(255)  NOT NULL,
    resource_name VARCHAR(255)  NOT NULL,
    state_hash    VARCHAR(64)   NOT NULL,
    prompt        TEXT          NOT NULL,
    response      TEXT          NOT NULL,
    model         VARCHAR(128)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_ai_diagnoses_state_hash ON ai_diagnoses (state_hash);
CREATE INDEX idx_ai_diagnoses_resource ON ai_diagnoses (resource_kind, resource_ns, resource_name);
