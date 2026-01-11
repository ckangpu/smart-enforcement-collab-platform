-- V11: admin bootstrap support (biz tags)

BEGIN;

ALTER TABLE project
  ADD COLUMN IF NOT EXISTS biz_tags text[] NOT NULL DEFAULT ARRAY[]::text[];

COMMIT;
