-- Remembers which chart reference (repo/chart) each Helm release was installed
-- from via KubeMind, so the Releases page can offer "edit values + upgrade"
-- without asking the user to re-specify the chart. Not live Kubernetes/Helm
-- state (that stays live via the `helm` CLI) — this is install provenance we
-- can't otherwise recover, same category as ai_diagnoses' derived data.
CREATE TABLE helm_installs (
    id            BIGSERIAL     PRIMARY KEY,
    cluster_id    BIGINT        NOT NULL,
    namespace     VARCHAR(255)  NOT NULL,
    release_name  VARCHAR(255)  NOT NULL,
    chart_ref     VARCHAR(512)  NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_helm_installs_release ON helm_installs (cluster_id, namespace, release_name);
