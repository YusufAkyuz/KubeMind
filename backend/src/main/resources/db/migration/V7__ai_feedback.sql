CREATE TABLE ai_feedback (
    id            BIGSERIAL     PRIMARY KEY,
    cluster_id    BIGINT,
    surface       VARCHAR(32)   NOT NULL,
    context_hash  VARCHAR(64)   NOT NULL,
    rating        VARCHAR(8)    NOT NULL,
    username      VARCHAR(255),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_feedback_context_hash ON ai_feedback (context_hash);
CREATE INDEX idx_ai_feedback_cluster ON ai_feedback (cluster_id, surface);
