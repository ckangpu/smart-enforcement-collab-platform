-- V3: client read-only API support (customer binding + complaints)

-- ---------- customer binding ----------
CREATE TABLE IF NOT EXISTS customer (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(256) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app_user
  ADD COLUMN IF NOT EXISTS customer_id uuid REFERENCES customer(id);

ALTER TABLE project
  ADD COLUMN IF NOT EXISTS customer_id uuid REFERENCES customer(id);

-- payment voucher (metadata only; never exposed to client directly)
ALTER TABLE payment
  ADD COLUMN IF NOT EXISTS voucher_file_id uuid REFERENCES file_store(id);
CREATE INDEX IF NOT EXISTS idx_payment_voucher_file ON payment(voucher_file_id);

-- ---------- reconcile complaints (对账疑问) ----------
CREATE TABLE IF NOT EXISTS reconcile_complaint (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES customer(id),
  project_id uuid REFERENCES project(id) ON DELETE SET NULL,
  payment_id uuid REFERENCES payment(id) ON DELETE SET NULL,
  status varchar(32) NOT NULL DEFAULT 'OPEN',
  title varchar(256) NOT NULL,
  message text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_reconcile_customer_time ON reconcile_complaint(customer_id, created_at);

ALTER TABLE reconcile_complaint ENABLE ROW LEVEL SECURITY;

-- ---------- extend RLS select policies for client (user_type=client + customer_id match) ----------

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
  OR EXISTS (SELECT 1
             FROM app_user u
             WHERE u.id = app_user_id()
               AND u.user_type = 'client'
               AND u.customer_id IS NOT NULL
               AND u.customer_id = project.customer_id)
);

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
  OR EXISTS (SELECT 1
             FROM app_user u
             JOIN project p ON p.id = "case".project_id
             WHERE u.id = app_user_id()
               AND u.user_type = 'client'
               AND u.customer_id IS NOT NULL
               AND u.customer_id = p.customer_id)
);

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
  OR EXISTS (SELECT 1
             FROM app_user u
             JOIN project p ON p.id = payment.project_id
             WHERE u.id = app_user_id()
               AND u.user_type = 'client'
               AND u.customer_id IS NOT NULL
               AND u.customer_id = p.customer_id)
);

-- reconcile_complaint
DROP POLICY IF EXISTS reconcile_select_policy ON reconcile_complaint;
CREATE POLICY reconcile_select_policy ON reconcile_complaint
FOR SELECT USING (
  app_is_admin()
  OR EXISTS (
    SELECT 1
    FROM app_user u
    WHERE u.id = app_user_id()
      AND u.user_type = 'client'
      AND u.customer_id = reconcile_complaint.customer_id
  )
);

-- read-only for clients; only admin can write/manage (minimal)
DROP POLICY IF EXISTS reconcile_admin_write_policy ON reconcile_complaint;
CREATE POLICY reconcile_admin_write_policy ON reconcile_complaint
FOR ALL USING (app_is_admin()) WITH CHECK (app_is_admin());
