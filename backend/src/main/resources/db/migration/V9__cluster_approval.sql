-- Self-service cluster registration by non-admin users needs an approval gate:
-- a USER-submitted kubeconfig sits PENDING until an ADMIN reviews and approves
-- it. ADMIN-submitted clusters are auto-approved (see ClusterService).
ALTER TABLE clusters ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE clusters ADD COLUMN reviewed_by VARCHAR(255);
ALTER TABLE clusters ADD COLUMN reviewed_at TIMESTAMPTZ;
