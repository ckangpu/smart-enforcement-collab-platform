-- V15: biz code sequencing by (prefix, yyyymm) + accepted_at + project inline fields

BEGIN;

-- ---------- sequence table ----------
CREATE TABLE IF NOT EXISTS biz_code_seq (
  prefix varchar(8) NOT NULL,
  yyyymm char(6) NOT NULL,
  last_seq int NOT NULL DEFAULT 0,
  PRIMARY KEY (prefix, yyyymm)
);

-- ---------- project fields ----------
ALTER TABLE project
  ADD COLUMN IF NOT EXISTS code varchar(32),
  ADD COLUMN IF NOT EXISTS accepted_at date,
  ADD COLUMN IF NOT EXISTS target_date date,
  ADD COLUMN IF NOT EXISTS owner_user_id uuid,
  ADD COLUMN IF NOT EXISTS lead_user_id uuid,
  ADD COLUMN IF NOT EXISTS assist_user_id uuid;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'fk_project_owner_user'
       AND conrelid = 'project'::regclass
  ) THEN
    EXECUTE 'ALTER TABLE project ADD CONSTRAINT fk_project_owner_user FOREIGN KEY (owner_user_id) REFERENCES app_user(id) ON DELETE SET NULL';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'fk_project_lead_user'
       AND conrelid = 'project'::regclass
  ) THEN
    EXECUTE 'ALTER TABLE project ADD CONSTRAINT fk_project_lead_user FOREIGN KEY (lead_user_id) REFERENCES app_user(id) ON DELETE SET NULL';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'fk_project_assist_user'
       AND conrelid = 'project'::regclass
  ) THEN
    EXECUTE 'ALTER TABLE project ADD CONSTRAINT fk_project_assist_user FOREIGN KEY (assist_user_id) REFERENCES app_user(id) ON DELETE SET NULL';
  END IF;
END $$;

-- ---------- case fields ----------
ALTER TABLE "case"
  ADD COLUMN IF NOT EXISTS code varchar(32),
  ADD COLUMN IF NOT EXISTS accepted_at date;

-- ---------- backfill existing codes (project prefix X, case prefix A) ----------
WITH ranked_project AS (
  SELECT
    id,
    to_char(coalesce(accepted_at, created_at)::date, 'YYYYMM') AS yyyymm,
    row_number() OVER (
      PARTITION BY to_char(coalesce(accepted_at, created_at)::date, 'YYYYMM')
      ORDER BY created_at, id
    ) AS rn
  FROM project
)
UPDATE project p
SET code = 'X' || r.yyyymm || lpad(r.rn::text, 4, '0')
FROM ranked_project r
WHERE p.id = r.id
  AND (p.code IS NULL OR p.code = '');

WITH ranked_case AS (
  SELECT
    id,
    to_char(coalesce(accepted_at, created_at)::date, 'YYYYMM') AS yyyymm,
    row_number() OVER (
      PARTITION BY to_char(coalesce(accepted_at, created_at)::date, 'YYYYMM')
      ORDER BY created_at, id
    ) AS rn
  FROM "case"
)
UPDATE "case" c
SET code = 'A' || r.yyyymm || lpad(r.rn::text, 4, '0')
FROM ranked_case r
WHERE c.id = r.id
  AND (c.code IS NULL OR c.code = '');

-- Seed sequence table with current max per (prefix, yyyymm)
WITH rp AS (
  SELECT
    to_char(coalesce(accepted_at, created_at)::date, 'YYYYMM') AS yyyymm,
    max((right(code, 4))::int) AS max_seq
  FROM project
  WHERE code IS NOT NULL AND length(code) >= 11
  GROUP BY 1
)
INSERT INTO biz_code_seq(prefix, yyyymm, last_seq)
SELECT 'X', rp.yyyymm, coalesce(rp.max_seq, 0)
FROM rp
ON CONFLICT (prefix, yyyymm) DO UPDATE SET last_seq = greatest(biz_code_seq.last_seq, excluded.last_seq);

WITH rc AS (
  SELECT
    to_char(coalesce(accepted_at, created_at)::date, 'YYYYMM') AS yyyymm,
    max((right(code, 4))::int) AS max_seq
  FROM "case"
  WHERE code IS NOT NULL AND length(code) >= 11
  GROUP BY 1
)
INSERT INTO biz_code_seq(prefix, yyyymm, last_seq)
SELECT 'A', rc.yyyymm, coalesce(rc.max_seq, 0)
FROM rc
ON CONFLICT (prefix, yyyymm) DO UPDATE SET last_seq = greatest(biz_code_seq.last_seq, excluded.last_seq);

-- ---------- uniqueness ----------
CREATE UNIQUE INDEX IF NOT EXISTS uq_project_code ON project(code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_case_code ON "case"(code);

-- Keep nullable accepted_at; but code should exist for new writes.
ALTER TABLE project
  ALTER COLUMN code SET NOT NULL;

ALTER TABLE "case"
  ALTER COLUMN code SET NOT NULL;

COMMIT;
