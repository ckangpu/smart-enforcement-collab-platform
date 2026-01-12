-- V14__password_login.sql
-- Add internal-only username+password login support.

ALTER TABLE app_user
  ADD COLUMN IF NOT EXISTS password_hash varchar(255) NULL,
  ADD COLUMN IF NOT EXISTS password_salt varchar(64) NULL,
  ADD COLUMN IF NOT EXISTS password_updated_at timestamptz NULL;

-- Enforce: only internal users may have password fields set.
-- (bcrypt already includes salt; password_salt is optional / reserved.)

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chk_app_user_password_internal_only'
  ) THEN
    ALTER TABLE app_user
      ADD CONSTRAINT chk_app_user_password_internal_only
      CHECK (
        user_type = 'internal'
        OR (
          password_hash IS NULL
          AND password_salt IS NULL
          AND password_updated_at IS NULL
        )
      );
  END IF;
END $$;
