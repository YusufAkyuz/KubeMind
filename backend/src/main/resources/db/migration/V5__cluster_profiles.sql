-- Derived, historical cluster knowledge for the AI ("Cluster Profile" background job) —
-- summaries/patterns/trends, never a live-state mirror. See ClusterProfileService.
CREATE TABLE cluster_profiles (
    id          BIGSERIAL     PRIMARY KEY,
    cluster_id  BIGINT        NOT NULL,
    section     VARCHAR(32)   NOT NULL,
    content     TEXT          NOT NULL,
    state_hash  VARCHAR(64),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_cluster_profiles_cluster_section ON cluster_profiles (cluster_id, section);
