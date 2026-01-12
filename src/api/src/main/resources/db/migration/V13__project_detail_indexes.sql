-- V13: indexes for project detail aggregation

BEGIN;

-- payment: project-level aggregates
CREATE INDEX IF NOT EXISTS idx_payment_project_paid_at
  ON payment(project_id, paid_at DESC);

COMMIT;
