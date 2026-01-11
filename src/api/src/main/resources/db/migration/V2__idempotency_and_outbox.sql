-- V2: API request idempotency + outbox error fields

-- ---------- idempotency ----------
CREATE TABLE IF NOT EXISTS idempotency_record (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  scope varchar(128) NOT NULL,
  idem_key varchar(128) NOT NULL,
  request_hash varchar(64),

  completed boolean NOT NULL DEFAULT false,
  status_code int,
  response_body jsonb,

  created_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),

  UNIQUE (user_id, scope, idem_key)
);

CREATE INDEX IF NOT EXISTS idx_idem_expires ON idempotency_record(expires_at);

ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS idem_select_policy ON idempotency_record;
CREATE POLICY idem_select_policy ON idempotency_record
FOR SELECT USING (app_is_admin() OR user_id = app_user_id());

DROP POLICY IF EXISTS idem_insert_policy ON idempotency_record;
CREATE POLICY idem_insert_policy ON idempotency_record
FOR INSERT WITH CHECK (app_is_admin() OR user_id = app_user_id());

DROP POLICY IF EXISTS idem_update_policy ON idempotency_record;
CREATE POLICY idem_update_policy ON idempotency_record
FOR UPDATE USING (app_is_admin() OR user_id = app_user_id())
WITH CHECK (app_is_admin() OR user_id = app_user_id());

-- ---------- outbox last_error ----------
ALTER TABLE event_outbox
  ADD COLUMN IF NOT EXISTS last_error text;
