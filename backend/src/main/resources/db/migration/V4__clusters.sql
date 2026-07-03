CREATE TABLE clusters (
    id                   BIGSERIAL     PRIMARY KEY,
    name                 VARCHAR(128)  NOT NULL UNIQUE,
    -- AES-256-GCM, base64(iv || ciphertext). Key comes from KUBEMIND_ENCRYPTION_KEY.
    kubeconfig_encrypted TEXT          NOT NULL,
    created_by           VARCHAR(255)  NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_checked_at      TIMESTAMPTZ,
    last_check_ok        BOOLEAN
);
