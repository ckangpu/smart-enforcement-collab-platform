-- V12: instruction_item status version for outbox de-dupe

BEGIN;

ALTER TABLE instruction_item
  ADD COLUMN IF NOT EXISTS status_version int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_instruction_item_status_version
  ON instruction_item(id, status_version);

COMMIT;
