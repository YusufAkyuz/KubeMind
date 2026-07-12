-- Runs once on first initdb — the extensions Flyway's V6 migration expects.
-- (Shared by docker-compose and anyone hand-rolling a Postgres for KubeMind.)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
