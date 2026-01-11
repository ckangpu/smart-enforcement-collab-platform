-- V10__pg_constraint_compat_fix.sql
-- Purpose: Add missing constraints in a PostgreSQL-compatible way (no "ADD CONSTRAINT IF NOT EXISTS")

DO $$
BEGIN
  -- Example: task constraint (adjust names to match your schema)
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chk_task_project_or_case'
  ) THEN
    ALTER TABLE task
      ADD CONSTRAINT chk_task_project_or_case
      CHECK (project_id IS NOT NULL);
  END IF;

  -- Add more constraints here if needed, using the same IF NOT EXISTS pattern.
END $$;
