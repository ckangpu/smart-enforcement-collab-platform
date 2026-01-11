-- V8: notifications (internal) + minimal fields for internal read APIs

BEGIN;

-- ---------- notification ----------
CREATE TABLE IF NOT EXISTS notification (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  user_id uuid NOT NULL REFERENCES app_user(id),
  type varchar(64) NOT NULL,
  title varchar(256) NOT NULL,
  body text NOT NULL,
  link varchar(512),
  status varchar(16) NOT NULL DEFAULT 'unread' CHECK (status IN ('unread','read')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_notification_user_status_time
  ON notification(user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_group_time
  ON notification(group_id, created_at DESC);

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS notification_select_policy ON notification;
CREATE POLICY notification_select_policy ON notification
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR user_id = app_user_id()
);

DROP POLICY IF EXISTS notification_insert_policy ON notification;
CREATE POLICY notification_insert_policy ON notification
FOR INSERT WITH CHECK (
  app_is_admin() OR group_id = ANY(app_group_ids())
);

DROP POLICY IF EXISTS notification_update_policy ON notification;
CREATE POLICY notification_update_policy ON notification
FOR UPDATE USING (
  app_is_admin() OR group_id = ANY(app_group_ids()) OR user_id = app_user_id()
)
WITH CHECK (
  app_is_admin() OR group_id = ANY(app_group_ids()) OR user_id = app_user_id()
);

DROP POLICY IF EXISTS notification_delete_policy ON notification;
CREATE POLICY notification_delete_policy ON notification
FOR DELETE USING (app_is_admin());

-- ---------- task: add minimal planning fields for internal read APIs ----------
ALTER TABLE task
  ADD COLUMN IF NOT EXISTS priority varchar(16),
  ADD COLUMN IF NOT EXISTS plan_end timestamptz;

CREATE INDEX IF NOT EXISTS idx_task_assignee_status_plan_end
  ON task(assignee_user_id, status, plan_end);

-- ---------- instruction_item: add assignee for notifications/overdue ----------
ALTER TABLE instruction_item
  ADD COLUMN IF NOT EXISTS assignee_user_id uuid REFERENCES app_user(id);

CREATE INDEX IF NOT EXISTS idx_instruction_item_assignee_due
  ON instruction_item(assignee_user_id, status, due_at);

COMMIT;
