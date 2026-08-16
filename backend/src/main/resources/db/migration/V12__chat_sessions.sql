-- Per-user, per-cluster chat history. Until now the chat was stateless: the
-- browser held the whole conversation and posted it back each turn, so a
-- reload — or logging in as someone else — lost it.
--
-- Only the conversation turns live here. The system prompt and its LIVE
-- CLUSTER SNAPSHOT are deliberately NOT stored: persisting them would mirror
-- live Kubernetes state into Postgres, and replaying an old session would
-- feed the model a stale cluster. The snapshot is rebuilt fresh every turn.
CREATE TABLE chat_sessions (
    id         UUID          PRIMARY KEY,
    -- Username rather than a users FK, matching runbooks/audit_log/ai_feedback.
    username   VARCHAR(255)  NOT NULL,
    cluster_id BIGINT        NOT NULL,
    title      VARCHAR(200)  NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The exact shape of the list query: this user's sessions, this cluster, newest first.
CREATE INDEX idx_chat_sessions_owner ON chat_sessions (username, cluster_id, updated_at DESC);

CREATE TABLE chat_messages (
    id         UUID          PRIMARY KEY,
    -- CASCADE so deleting a session takes its transcript with it in one statement.
    session_id UUID          NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role       VARCHAR(16)   NOT NULL,
    content    TEXT          NOT NULL,
    -- Provenance, as in ai_diagnoses. NULL on user turns.
    model      VARCHAR(128),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_messages_session ON chat_messages (session_id, created_at);
