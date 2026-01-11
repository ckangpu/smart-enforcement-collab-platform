-- V9: project KPI denominators (for zone dashboard payment ratios)

BEGIN;

ALTER TABLE project
  ADD COLUMN IF NOT EXISTS execution_target_amount numeric(18,2);

ALTER TABLE project
  ADD COLUMN IF NOT EXISTS mandate_amount numeric(18,2);

COMMIT;
