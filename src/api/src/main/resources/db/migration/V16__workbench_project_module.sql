-- V16: Workbench project module (creditor/debtor/case tables + attachments)

BEGIN;

-- -------------------- project extra fields --------------------
ALTER TABLE project
  ADD COLUMN IF NOT EXISTS entrustor varchar(256),
  ADD COLUMN IF NOT EXISTS progress_status varchar(16) NOT NULL DEFAULT 'NOT_STARTED'
    CHECK (progress_status IN ('NOT_STARTED','IN_PROGRESS','STALLED','DONE')),
  ADD COLUMN IF NOT EXISTS note text;

CREATE INDEX IF NOT EXISTS idx_project_progress_status ON project(progress_status);
CREATE INDEX IF NOT EXISTS idx_project_owner_user_id ON project(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_project_lead_user_id ON project(lead_user_id);
CREATE INDEX IF NOT EXISTS idx_project_assist_user_id ON project(assist_user_id);

-- -------------------- creditor / debtor --------------------
CREATE TABLE IF NOT EXISTS project_creditor (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  sr_code varchar(16) NOT NULL,
  name varchar(256) NOT NULL,
  id_no varchar(64),
  unified_code varchar(64),
  reg_address varchar(512),
  e_delivery_phone varchar(32),
  mail_address varchar(512),
  mail_recipient varchar(128),
  mail_phone varchar(32),
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (sr_code)
);
CREATE INDEX IF NOT EXISTS idx_project_creditor_project ON project_creditor(project_id);
CREATE INDEX IF NOT EXISTS idx_project_creditor_group ON project_creditor(group_id);
CREATE INDEX IF NOT EXISTS idx_project_creditor_id_no ON project_creditor(id_no);
CREATE INDEX IF NOT EXISTS idx_project_creditor_unified_code ON project_creditor(unified_code);

CREATE TABLE IF NOT EXISTS project_debtor (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  br_code varchar(16) NOT NULL,
  name varchar(256) NOT NULL,
  id_no varchar(64),
  unified_code varchar(64),
  reg_address varchar(512),
  e_delivery_phone varchar(32),
  mail_address varchar(512),
  mail_recipient varchar(128),
  mail_phone varchar(32),
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (br_code)
);
CREATE INDEX IF NOT EXISTS idx_project_debtor_project ON project_debtor(project_id);
CREATE INDEX IF NOT EXISTS idx_project_debtor_group ON project_debtor(group_id);
CREATE INDEX IF NOT EXISTS idx_project_debtor_id_no ON project_debtor(id_no);
CREATE INDEX IF NOT EXISTS idx_project_debtor_unified_code ON project_debtor(unified_code);

-- -------------------- extend case for workbench info --------------------
ALTER TABLE "case"
  ADD COLUMN IF NOT EXISTS creditor_id uuid REFERENCES project_creditor(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS debtor_id uuid REFERENCES project_debtor(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS cause varchar(256),
  ADD COLUMN IF NOT EXISTS basis_doc_type varchar(32),
  ADD COLUMN IF NOT EXISTS basis_doc_no varchar(128),
  ADD COLUMN IF NOT EXISTS basis_org varchar(256),
  ADD COLUMN IF NOT EXISTS basis_main_text text;

CREATE INDEX IF NOT EXISTS idx_case_creditor ON "case"(creditor_id);
CREATE INDEX IF NOT EXISTS idx_case_debtor ON "case"(debtor_id);

-- -------------------- case procedure --------------------
CREATE TABLE IF NOT EXISTS case_procedure (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,
  name varchar(64) NOT NULL,
  doc_no varchar(128),
  org varchar(256),
  decided_at date,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_case_procedure_case ON case_procedure(case_id);

-- -------------------- debtor clues (XS) --------------------
CREATE TABLE IF NOT EXISTS debtor_clue (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  debtor_id uuid NOT NULL REFERENCES project_debtor(id) ON DELETE CASCADE,
  xs_code varchar(16) NOT NULL,
  category varchar(32) NOT NULL,
  detail text NOT NULL,
  source varchar(128),
  collected_at date,
  collector_user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (xs_code)
);
CREATE INDEX IF NOT EXISTS idx_debtor_clue_debtor ON debtor_clue(debtor_id);
CREATE INDEX IF NOT EXISTS idx_debtor_clue_group ON debtor_clue(group_id);

-- -------------------- measures (control / sanction) --------------------
CREATE TABLE IF NOT EXISTS case_measure_control (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,
  name varchar(128) NOT NULL,
  target varchar(256),
  basis_org varchar(256),
  basis_doc_no varchar(128),
  basis_doc_name varchar(256),
  content text,
  result text,
  rank_no varchar(64),
  due_at date,
  note text,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_case_measure_control_case ON case_measure_control(case_id);
CREATE INDEX IF NOT EXISTS idx_case_measure_control_due ON case_measure_control(due_at);

CREATE TABLE IF NOT EXISTS case_measure_sanction (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,
  name varchar(128) NOT NULL,
  target varchar(256),
  basis_org varchar(256),
  basis_doc_no varchar(128),
  basis_doc_name varchar(256),
  content text,
  result text,
  due_at date,
  note text,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_case_measure_sanction_case ON case_measure_sanction(case_id);
CREATE INDEX IF NOT EXISTS idx_case_measure_sanction_due ON case_measure_sanction(due_at);

-- -------------------- case costs --------------------
CREATE TABLE IF NOT EXISTS case_cost (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  case_id uuid NOT NULL REFERENCES "case"(id) ON DELETE CASCADE,
  category varchar(64) NOT NULL,
  amount numeric(18,2) NOT NULL DEFAULT 0,
  occurred_at date,
  payer varchar(128),
  note text,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_case_cost_case ON case_cost(case_id);
CREATE INDEX IF NOT EXISTS idx_case_cost_occurred ON case_cost(occurred_at);

-- -------------------- attachment link --------------------
CREATE TABLE IF NOT EXISTS attachment_link (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  object_type varchar(32) NOT NULL,
  object_id uuid NOT NULL,
  file_id uuid NOT NULL REFERENCES file_store(id) ON DELETE CASCADE,
  title varchar(256),
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_attachment_link_obj ON attachment_link(object_type, object_id);
CREATE INDEX IF NOT EXISTS idx_attachment_link_file ON attachment_link(file_id);

-- -------------------- RLS enable --------------------
ALTER TABLE project_creditor ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_debtor ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_procedure ENABLE ROW LEVEL SECURITY;
ALTER TABLE debtor_clue ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_measure_control ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_measure_sanction ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_cost ENABLE ROW LEVEL SECURITY;
ALTER TABLE attachment_link ENABLE ROW LEVEL SECURITY;

-- -------------------- RLS policies: creditor (follows project) --------------------
DROP POLICY IF EXISTS project_creditor_select_policy ON project_creditor;
CREATE POLICY project_creditor_select_policy ON project_creditor
FOR SELECT USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM project p WHERE p.id = project_creditor.project_id)
);

DROP POLICY IF EXISTS project_creditor_insert_policy ON project_creditor;
CREATE POLICY project_creditor_insert_policy ON project_creditor
FOR INSERT WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM project p WHERE p.id = project_creditor.project_id)
);

DROP POLICY IF EXISTS project_creditor_update_policy ON project_creditor;
CREATE POLICY project_creditor_update_policy ON project_creditor
FOR UPDATE USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM project p WHERE p.id = project_creditor.project_id)
)
WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM project p WHERE p.id = project_creditor.project_id)
);

DROP POLICY IF EXISTS project_creditor_delete_policy ON project_creditor;
CREATE POLICY project_creditor_delete_policy ON project_creditor
FOR DELETE USING (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM project p WHERE p.id = project_creditor.project_id)
);

-- -------------------- RLS policies: debtor (follows project) --------------------
DROP POLICY IF EXISTS project_debtor_select_policy ON project_debtor;
CREATE POLICY project_debtor_select_policy ON project_debtor
FOR SELECT USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM project p WHERE p.id = project_debtor.project_id)
);

DROP POLICY IF EXISTS project_debtor_insert_policy ON project_debtor;
CREATE POLICY project_debtor_insert_policy ON project_debtor
FOR INSERT WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM project p WHERE p.id = project_debtor.project_id)
);

DROP POLICY IF EXISTS project_debtor_update_policy ON project_debtor;
CREATE POLICY project_debtor_update_policy ON project_debtor
FOR UPDATE USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM project p WHERE p.id = project_debtor.project_id)
)
WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM project p WHERE p.id = project_debtor.project_id)
);

DROP POLICY IF EXISTS project_debtor_delete_policy ON project_debtor;
CREATE POLICY project_debtor_delete_policy ON project_debtor
FOR DELETE USING (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM project p WHERE p.id = project_debtor.project_id)
);

-- -------------------- RLS policies: case_procedure (follows case) --------------------
DROP POLICY IF EXISTS case_procedure_select_policy ON case_procedure;
CREATE POLICY case_procedure_select_policy ON case_procedure
FOR SELECT USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_procedure.case_id)
);

DROP POLICY IF EXISTS case_procedure_insert_policy ON case_procedure;
CREATE POLICY case_procedure_insert_policy ON case_procedure
FOR INSERT WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_procedure.case_id)
);

DROP POLICY IF EXISTS case_procedure_update_policy ON case_procedure;
CREATE POLICY case_procedure_update_policy ON case_procedure
FOR UPDATE USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_procedure.case_id)
)
WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_procedure.case_id)
);

DROP POLICY IF EXISTS case_procedure_delete_policy ON case_procedure;
CREATE POLICY case_procedure_delete_policy ON case_procedure
FOR DELETE USING (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_procedure.case_id)
);

-- -------------------- RLS policies: debtor_clue (follows debtor -> project) --------------------
DROP POLICY IF EXISTS debtor_clue_select_policy ON debtor_clue;
CREATE POLICY debtor_clue_select_policy ON debtor_clue
FOR SELECT USING (
  app_is_admin()
  OR EXISTS (
    SELECT 1
      from project_debtor d
      join project p on p.id = d.project_id
     where d.id = debtor_clue.debtor_id
  )
);

DROP POLICY IF EXISTS debtor_clue_insert_policy ON debtor_clue;
CREATE POLICY debtor_clue_insert_policy ON debtor_clue
FOR INSERT WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (
    SELECT 1
      from project_debtor d
      join project p on p.id = d.project_id
     where d.id = debtor_clue.debtor_id
  )
);

DROP POLICY IF EXISTS debtor_clue_update_policy ON debtor_clue;
CREATE POLICY debtor_clue_update_policy ON debtor_clue
FOR UPDATE USING (
  app_is_admin()
  OR EXISTS (
    SELECT 1
      from project_debtor d
      join project p on p.id = d.project_id
     where d.id = debtor_clue.debtor_id
  )
)
WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (
    SELECT 1
      from project_debtor d
      join project p on p.id = d.project_id
     where d.id = debtor_clue.debtor_id
  )
);

DROP POLICY IF EXISTS debtor_clue_delete_policy ON debtor_clue;
CREATE POLICY debtor_clue_delete_policy ON debtor_clue
FOR DELETE USING (
  app_can_write_group(group_id)
  AND EXISTS (
    SELECT 1
      from project_debtor d
      join project p on p.id = d.project_id
     where d.id = debtor_clue.debtor_id
  )
);

-- -------------------- RLS policies: measures & costs (follow case) --------------------
DROP POLICY IF EXISTS case_measure_control_select_policy ON case_measure_control;
CREATE POLICY case_measure_control_select_policy ON case_measure_control
FOR SELECT USING (app_is_admin() OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_control.case_id));
DROP POLICY IF EXISTS case_measure_control_insert_policy ON case_measure_control;
CREATE POLICY case_measure_control_insert_policy ON case_measure_control
FOR INSERT WITH CHECK (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_control.case_id));
DROP POLICY IF EXISTS case_measure_control_update_policy ON case_measure_control;
CREATE POLICY case_measure_control_update_policy ON case_measure_control
FOR UPDATE USING (app_is_admin() OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_control.case_id))
WITH CHECK (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_control.case_id));
DROP POLICY IF EXISTS case_measure_control_delete_policy ON case_measure_control;
CREATE POLICY case_measure_control_delete_policy ON case_measure_control
FOR DELETE USING (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_control.case_id));

DROP POLICY IF EXISTS case_measure_sanction_select_policy ON case_measure_sanction;
CREATE POLICY case_measure_sanction_select_policy ON case_measure_sanction
FOR SELECT USING (app_is_admin() OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_sanction.case_id));
DROP POLICY IF EXISTS case_measure_sanction_insert_policy ON case_measure_sanction;
CREATE POLICY case_measure_sanction_insert_policy ON case_measure_sanction
FOR INSERT WITH CHECK (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_sanction.case_id));
DROP POLICY IF EXISTS case_measure_sanction_update_policy ON case_measure_sanction;
CREATE POLICY case_measure_sanction_update_policy ON case_measure_sanction
FOR UPDATE USING (app_is_admin() OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_sanction.case_id))
WITH CHECK (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_sanction.case_id));
DROP POLICY IF EXISTS case_measure_sanction_delete_policy ON case_measure_sanction;
CREATE POLICY case_measure_sanction_delete_policy ON case_measure_sanction
FOR DELETE USING (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_measure_sanction.case_id));

DROP POLICY IF EXISTS case_cost_select_policy ON case_cost;
CREATE POLICY case_cost_select_policy ON case_cost
FOR SELECT USING (app_is_admin() OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_cost.case_id));
DROP POLICY IF EXISTS case_cost_insert_policy ON case_cost;
CREATE POLICY case_cost_insert_policy ON case_cost
FOR INSERT WITH CHECK (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_cost.case_id));
DROP POLICY IF EXISTS case_cost_update_policy ON case_cost;
CREATE POLICY case_cost_update_policy ON case_cost
FOR UPDATE USING (app_is_admin() OR EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_cost.case_id))
WITH CHECK (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_cost.case_id));
DROP POLICY IF EXISTS case_cost_delete_policy ON case_cost;
CREATE POLICY case_cost_delete_policy ON case_cost
FOR DELETE USING (app_can_write_group(group_id) AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = case_cost.case_id));

-- -------------------- RLS policies: attachment_link (by object type) --------------------
DROP POLICY IF EXISTS attachment_link_select_policy ON attachment_link;
CREATE POLICY attachment_link_select_policy ON attachment_link
FOR SELECT USING (
  app_is_admin()
  OR (
    object_type = 'project' AND EXISTS (SELECT 1 FROM project p WHERE p.id = attachment_link.object_id)
  )
  OR (
    object_type = 'case' AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = attachment_link.object_id)
  )
  OR (
    object_type = 'project_creditor' AND EXISTS (SELECT 1 FROM project_creditor pc WHERE pc.id = attachment_link.object_id)
  )
  OR (
    object_type = 'project_debtor' AND EXISTS (SELECT 1 FROM project_debtor pd WHERE pd.id = attachment_link.object_id)
  )
  OR (
    object_type = 'debtor_clue' AND EXISTS (SELECT 1 FROM debtor_clue dc WHERE dc.id = attachment_link.object_id)
  )
  OR (
    object_type = 'case_procedure' AND EXISTS (SELECT 1 FROM case_procedure cp WHERE cp.id = attachment_link.object_id)
  )
  OR (
    object_type = 'case_measure_control' AND EXISTS (SELECT 1 FROM case_measure_control mc WHERE mc.id = attachment_link.object_id)
  )
  OR (
    object_type = 'case_measure_sanction' AND EXISTS (SELECT 1 FROM case_measure_sanction ms WHERE ms.id = attachment_link.object_id)
  )
  OR (
    object_type = 'case_cost' AND EXISTS (SELECT 1 FROM case_cost cc WHERE cc.id = attachment_link.object_id)
  )
);

DROP POLICY IF EXISTS attachment_link_insert_policy ON attachment_link;
CREATE POLICY attachment_link_insert_policy ON attachment_link
FOR INSERT WITH CHECK (
  app_can_write_group(group_id)
  AND (
    (object_type = 'project' AND EXISTS (SELECT 1 FROM project p WHERE p.id = attachment_link.object_id))
    OR (object_type = 'case' AND EXISTS (SELECT 1 FROM "case" c WHERE c.id = attachment_link.object_id))
    OR (object_type = 'project_creditor' AND EXISTS (SELECT 1 FROM project_creditor pc WHERE pc.id = attachment_link.object_id))
    OR (object_type = 'project_debtor' AND EXISTS (SELECT 1 FROM project_debtor pd WHERE pd.id = attachment_link.object_id))
    OR (object_type = 'debtor_clue' AND EXISTS (SELECT 1 FROM debtor_clue dc WHERE dc.id = attachment_link.object_id))
    OR (object_type = 'case_procedure' AND EXISTS (SELECT 1 FROM case_procedure cp WHERE cp.id = attachment_link.object_id))
    OR (object_type = 'case_measure_control' AND EXISTS (SELECT 1 FROM case_measure_control mc WHERE mc.id = attachment_link.object_id))
    OR (object_type = 'case_measure_sanction' AND EXISTS (SELECT 1 FROM case_measure_sanction ms WHERE ms.id = attachment_link.object_id))
    OR (object_type = 'case_cost' AND EXISTS (SELECT 1 FROM case_cost cc WHERE cc.id = attachment_link.object_id))
  )
);

DROP POLICY IF EXISTS attachment_link_delete_policy ON attachment_link;
CREATE POLICY attachment_link_delete_policy ON attachment_link
FOR DELETE USING (app_can_write_group(group_id));

COMMIT;
