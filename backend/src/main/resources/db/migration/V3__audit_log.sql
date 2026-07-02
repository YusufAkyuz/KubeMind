CREATE TABLE audit_log (
    id           BIGSERIAL     PRIMARY KEY,
    -- Nullable FK: the audit row must outlive the user account.
    user_id      BIGINT        REFERENCES users (id) ON DELETE SET NULL,
    username     VARCHAR(255)  NOT NULL,
    -- Single-cluster for now; column exists so multi-cluster (Phase 5) is additive.
    cluster_id   BIGINT,
    action       VARCHAR(64)   NOT NULL,
    resource_ref VARCHAR(512)  NOT NULL,
    payload      TEXT,
    result       VARCHAR(512)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at DESC);
CREATE INDEX idx_audit_log_username ON audit_log (username);
