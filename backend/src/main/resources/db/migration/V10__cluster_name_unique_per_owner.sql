-- Clusters are now self-service (see V9): each user registers and sees only
-- their own. A global UNIQUE on name made two different users collide over an
-- everyday name like "staging" — uniqueness should be scoped to the owner.
ALTER TABLE clusters DROP CONSTRAINT clusters_name_key;
CREATE UNIQUE INDEX clusters_created_by_name_key ON clusters (created_by, name);
