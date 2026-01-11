-- Flyway V1 init (minimal runnable schema for V1)
-- NOTE: Flyway will run migrations in a transaction on Postgres.

-- ---------- extensions ----------
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------- app db role (non-superuser) ----------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'secp_app') THEN
    CREATE ROLE secp_app LOGIN PASSWORD 'secp_app';
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO secp_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO secp_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO secp_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO secp_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO secp_app;

-- ---------- helper functions for RLS ----------
CREATE OR REPLACE FUNCTION app_user_id()
RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid;
$$;

CREATE OR REPLACE FUNCTION app_is_admin()
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT COALESCE(NULLIF(current_setting('app.is_admin', true), '')::boolean, false);
$$;

CREATE OR REPLACE FUNCTION app_group_ids()
RETURNS uuid[] LANGUAGE sql STABLE AS $$
  SELECT COALESCE(
    string_to_array(NULLIF(current_setting('app.group_ids', true), ''), ',')::uuid[],
    ARRAY[]::uuid[]
  );
$$;

CREATE OR REPLACE FUNCTION app_can_write_group(gid uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT app_is_admin() OR gid = ANY(app_group_ids());
$$;

-- ---------- core tables ----------
CREATE TABLE IF NOT EXISTS app_user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  phone varchar(32) UNIQUE NOT NULL,
  username varchar(64) NOT NULL,
  user_type varchar(16) NOT NULL CHECK (user_type IN ('internal','client','external')),
  is_admin boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_group (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(128) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_group (
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  group_id uuid NOT NULL REFERENCES app_group(id) ON DELETE CASCADE,
  role_code varchar(32) NOT NULL DEFAULT 'member',
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, group_id)
);

-- temp grant for cross-zone visibility/edit
CREATE TABLE IF NOT EXISTS temp_grant (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  object_type varchar(32) NOT NULL,
  object_id uuid NOT NULL,
  permission_set jsonb NOT NULL DEFAULT '{}'::jsonb,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_temp_grant_user_obj ON temp_grant(user_id, object_type, object_id);
CREATE INDEX IF NOT EXISTS idx_temp_grant_expires ON temp_grant(expires_at);

-- project / case
CREATE TABLE IF NOT EXISTS project (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  name varchar(256) NOT NULL,
  status varchar(32) NOT NULL DEFAULT 'ACTIVE',
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_project_group ON project(group_id);

CREATE TABLE IF NOT EXISTS project_member (
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  member_role varchar(32) NOT NULL DEFAULT 'member',
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, user_id)
);

CREATE TABLE IF NOT EXISTS "case" (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  title varchar(256) NOT NULL,
  status varchar(32) NOT NULL DEFAULT 'OPEN',
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_case_group ON "case"(group_id);
CREATE INDEX IF NOT EXISTS idx_case_project ON "case"(project_id);

CREATE TABLE IF NOT EXISTS case_member (
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  member_role varchar(32) NOT NULL DEFAULT 'assignee',
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (case_id, user_id)
);

-- task
CREATE TABLE IF NOT EXISTS task (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,
  title varchar(256) NOT NULL,
  status varchar(32) NOT NULL DEFAULT 'TODO',
  assignee_user_id uuid REFERENCES app_user(id),
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_group ON task(group_id);
CREATE INDEX IF NOT EXISTS idx_task_case ON task(case_id);

-- payment (immutable core fields)
CREATE TABLE IF NOT EXISTS payment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,

  amount numeric(18,2) NOT NULL,
  paid_at timestamptz NOT NULL,
  pay_channel varchar(32) NOT NULL,
  payer_name varchar(128) NOT NULL,
  bank_last4 varchar(8),

  corrected_from_payment_id uuid REFERENCES payment(id),
  correction_reason varchar(512),

  client_note text,
  internal_note text,
  is_client_visible boolean NOT NULL DEFAULT true,

  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payment_group ON payment(group_id);
CREATE INDEX IF NOT EXISTS idx_payment_case ON payment(case_id);
CREATE INDEX IF NOT EXISTS idx_payment_corrected_from ON payment(corrected_from_payment_id);

-- file store (minimal)
CREATE TABLE IF NOT EXISTS file_store (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  case_id uuid REFERENCES "case"(id) ON DELETE CASCADE,
  filename varchar(256) NOT NULL,
  content_type varchar(128),
  size_bytes bigint NOT NULL DEFAULT 0,
  sha256 varchar(64),
  s3_key_raw varchar(512) NOT NULL,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_file_group ON file_store(group_id);
CREATE INDEX IF NOT EXISTS idx_file_case ON file_store(case_id);

-- audit log
CREATE TABLE IF NOT EXISTS audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid,
  actor_user_id uuid REFERENCES app_user(id),
  action varchar(64) NOT NULL,
  object_type varchar(32) NOT NULL,
  object_id uuid,
  request_id varchar(64),
  ip varchar(64),
  user_agent text,
  summary jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit_log(actor_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_object ON audit_log(object_type, object_id);

-- outbox
CREATE TABLE IF NOT EXISTS event_outbox (
  event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type varchar(64) NOT NULL,
  dedupe_key varchar(256) NOT NULL,
  group_id uuid,
  project_id uuid,
  case_id uuid,
  actor_user_id uuid,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processing','done','failed')),
  retry_count int NOT NULL DEFAULT 0,
  next_run_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_dedupe_key ON event_outbox(dedupe_key);
CREATE INDEX IF NOT EXISTS idx_outbox_status_next ON event_outbox(status, next_run_at, created_at);

-- outbox consumption de-dupe
CREATE TABLE IF NOT EXISTS event_consumption (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id uuid NOT NULL REFERENCES event_outbox(event_id) ON DELETE CASCADE,
  handler_name varchar(128) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'started' CHECK (status IN ('started','done','failed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (event_id, handler_name)
);

-- ---------- RLS enable ----------
ALTER TABLE project ENABLE ROW LEVEL SECURITY;
ALTER TABLE "case" ENABLE ROW LEVEL SECURITY;
ALTER TABLE task ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_store ENABLE ROW LEVEL SECURITY;
ALTER TABLE temp_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_outbox ENABLE ROW LEVEL SECURITY;

-- ---------- RLS policies ----------
-- project
DROP POLICY IF EXISTS project_select_policy ON project;
CREATE POLICY project_select_policy ON project
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = project.id AND pm.user_id = app_user_id())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'project'
               AND tg.object_id = project.id
               AND tg.expires_at > now())
);

DROP POLICY IF EXISTS project_insert_policy ON project;
CREATE POLICY project_insert_policy ON project
FOR INSERT WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS project_update_policy ON project;
CREATE POLICY project_update_policy ON project
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = project.id AND pm.user_id = app_user_id())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'project'
               AND tg.object_id = project.id
               AND tg.expires_at > now())
)
WITH CHECK (app_can_write_group(group_id));

-- case
DROP POLICY IF EXISTS case_select_policy ON "case";
CREATE POLICY case_select_policy ON "case"
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM case_member cm WHERE cm.case_id = "case".id AND cm.user_id = app_user_id())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'case'
               AND tg.object_id = "case".id
               AND tg.expires_at > now())
);

DROP POLICY IF EXISTS case_insert_policy ON "case";
CREATE POLICY case_insert_policy ON "case"
FOR INSERT WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS case_update_policy ON "case";
CREATE POLICY case_update_policy ON "case"
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'case'
               AND tg.object_id = "case".id
               AND tg.expires_at > now()
               AND (tg.permission_set->>'can_edit') = 'true')
)
WITH CHECK (app_can_write_group(group_id));

-- task
DROP POLICY IF EXISTS task_select_policy ON task;
CREATE POLICY task_select_policy ON task
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR assignee_user_id = app_user_id()
  OR EXISTS (SELECT 1 FROM case_member cm WHERE cm.case_id = task.case_id AND cm.user_id = app_user_id())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'task'
               AND tg.object_id = task.id
               AND tg.expires_at > now())
);

DROP POLICY IF EXISTS task_insert_policy ON task;
CREATE POLICY task_insert_policy ON task
FOR INSERT WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS task_update_policy ON task;
CREATE POLICY task_update_policy ON task
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR assignee_user_id = app_user_id()
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'task'
               AND tg.object_id = task.id
               AND tg.expires_at > now()
               AND (tg.permission_set->>'can_edit') = 'true')
)
WITH CHECK (app_can_write_group(group_id));

-- payment
DROP POLICY IF EXISTS payment_select_policy ON payment;
CREATE POLICY payment_select_policy ON payment
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM case_member cm WHERE cm.case_id = payment.case_id AND cm.user_id = app_user_id())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'payment'
               AND tg.object_id = payment.id
               AND tg.expires_at > now())
);

DROP POLICY IF EXISTS payment_insert_policy ON payment;
CREATE POLICY payment_insert_policy ON payment
FOR INSERT WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS payment_update_policy ON payment;
CREATE POLICY payment_update_policy ON payment
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'payment'
               AND tg.object_id = payment.id
               AND tg.expires_at > now()
               AND (tg.permission_set->>'can_edit') = 'true')
)
WITH CHECK (app_can_write_group(group_id));

-- file_store
DROP POLICY IF EXISTS file_select_policy ON file_store;
CREATE POLICY file_select_policy ON file_store
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR created_by = app_user_id()
  OR EXISTS (SELECT 1 FROM temp_grant tg
             WHERE tg.user_id = app_user_id()
               AND tg.object_type = 'file'
               AND tg.object_id = file_store.id
               AND tg.expires_at > now())
);

DROP POLICY IF EXISTS file_insert_policy ON file_store;
CREATE POLICY file_insert_policy ON file_store
FOR INSERT WITH CHECK (app_can_write_group(group_id));

-- audit_log: only admin can read, everyone can insert
DROP POLICY IF EXISTS audit_select_policy ON audit_log;
CREATE POLICY audit_select_policy ON audit_log
FOR SELECT USING (app_is_admin());

DROP POLICY IF EXISTS audit_insert_policy ON audit_log;
CREATE POLICY audit_insert_policy ON audit_log
FOR INSERT WITH CHECK (true);

-- temp_grant: only admin can manage
DROP POLICY IF EXISTS temp_grant_admin_policy ON temp_grant;
CREATE POLICY temp_grant_admin_policy ON temp_grant
FOR ALL USING (app_is_admin()) WITH CHECK (app_is_admin());

-- outbox: allow insert by anyone (事务内)，select/update 仅 admin
DROP POLICY IF EXISTS outbox_insert_policy ON event_outbox;
CREATE POLICY outbox_insert_policy ON event_outbox
FOR INSERT WITH CHECK (true);

DROP POLICY IF EXISTS outbox_select_policy ON event_outbox;
CREATE POLICY outbox_select_policy ON event_outbox
FOR SELECT USING (app_is_admin());

DROP POLICY IF EXISTS outbox_update_policy ON event_outbox;
CREATE POLICY outbox_update_policy ON event_outbox
FOR UPDATE USING (app_is_admin()) WITH CHECK (app_is_admin());

-- ---------- payment immutability trigger ----------
CREATE OR REPLACE FUNCTION prevent_payment_core_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.amount IS DISTINCT FROM OLD.amount)
     OR (NEW.paid_at IS DISTINCT FROM OLD.paid_at)
     OR (NEW.pay_channel IS DISTINCT FROM OLD.pay_channel)
     OR (NEW.payer_name IS DISTINCT FROM OLD.payer_name)
     OR (NEW.bank_last4 IS DISTINCT FROM OLD.bank_last4)
     OR (NEW.case_id IS DISTINCT FROM OLD.case_id)
     OR (NEW.project_id IS DISTINCT FROM OLD.project_id)
     OR (NEW.group_id IS DISTINCT FROM OLD.group_id)
  THEN
     RAISE EXCEPTION 'payment core fields are immutable; use correction flow';
  END IF;

  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prevent_payment_core_update ON payment;
CREATE TRIGGER trg_prevent_payment_core_update
BEFORE UPDATE ON payment
FOR EACH ROW
EXECUTE FUNCTION prevent_payment_core_update();
