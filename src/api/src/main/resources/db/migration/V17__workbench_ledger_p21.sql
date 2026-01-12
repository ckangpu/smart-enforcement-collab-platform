-- V17: Workbench ledger (P2.1) fields

BEGIN;

-- Add note fields for parties
ALTER TABLE project_creditor
  ADD COLUMN IF NOT EXISTS note text;

ALTER TABLE project_debtor
  ADD COLUMN IF NOT EXISTS note text;

-- Add execution basis decided date for cases
ALTER TABLE "case"
  ADD COLUMN IF NOT EXISTS basis_decided_at date;

COMMIT;
