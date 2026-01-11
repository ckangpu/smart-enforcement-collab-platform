-- V4: client payment dispute write support (type + SLA + RLS insert)

-- add complaint type + SLA due
ALTER TABLE reconcile_complaint
  ADD COLUMN IF NOT EXISTS type varchar(32) NOT NULL DEFAULT 'general',
  ADD COLUMN IF NOT EXISTS sla_due_at timestamptz NOT NULL DEFAULT (now() + interval '48 hours');

-- allow client to create payment disputes for own customer (minimal)
DROP POLICY IF EXISTS reconcile_client_insert_policy ON reconcile_complaint;
CREATE POLICY reconcile_client_insert_policy ON reconcile_complaint
FOR INSERT WITH CHECK (
  EXISTS (
    SELECT 1
    FROM app_user u
    WHERE u.id = app_user_id()
      AND u.user_type = 'client'
      AND u.customer_id = reconcile_complaint.customer_id
  )
  AND reconcile_complaint.type = 'payment_dispute'
);
