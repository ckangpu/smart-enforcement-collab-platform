-- V7: instruction + project-only task + evidence

BEGIN;

-- ---------- task: allow project-only ----------
ALTER TABLE task
  ADD COLUMN IF NOT EXISTS project_id uuid;

-- Backfill project_id for existing tasks
UPDATE task t
SET project_id = c.project_id
FROM "case" c
WHERE t.case_id = c.id
  AND t.project_id IS NULL;

ALTER TABLE task
  ALTER COLUMN case_id DROP NOT NULL;

ALTER TABLE task
  ALTER COLUMN project_id SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM pg_constraint
     WHERE conname = 'chk_task_project_or_case'
       AND conrelid = 'task'::regclass
  ) THEN
    EXECUTE 'ALTER TABLE task ADD CONSTRAINT chk_task_project_or_case CHECK (project_id IS NOT NULL)';
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_task_project ON task(project_id);

-- Link task to instruction item (optional)
ALTER TABLE task
  ADD COLUMN IF NOT EXISTS instruction_item_id uuid;

CREATE INDEX IF NOT EXISTS idx_task_instruction_item ON task(instruction_item_id);

-- ---------- instruction ----------
CREATE TABLE IF NOT EXISTS instruction (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  ref_type varchar(16) NOT NULL CHECK (ref_type IN ('project','case')),
  ref_id uuid NOT NULL,
  title varchar(256) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ISSUED')),
  version int NOT NULL DEFAULT 0,
  issued_by uuid REFERENCES app_user(id),
  issued_at timestamptz,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_instruction_group ON instruction(group_id);
CREATE INDEX IF NOT EXISTS idx_instruction_ref ON instruction(ref_type, ref_id);

CREATE TABLE IF NOT EXISTS instruction_item (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  instruction_id uuid NOT NULL REFERENCES instruction(id) ON DELETE CASCADE,
  group_id uuid NOT NULL REFERENCES app_group(id),
  title varchar(256) NOT NULL,
  due_at timestamptz,
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DONE')),
  done_by uuid REFERENCES app_user(id),
  done_at timestamptz,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_instruction_item_instruction ON instruction_item(instruction_id);
CREATE INDEX IF NOT EXISTS idx_instruction_item_due ON instruction_item(due_at, status);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM pg_constraint
     WHERE conname = 'fk_task_instruction_item'
       AND conrelid = 'task'::regclass
  ) THEN
    EXECUTE 'ALTER TABLE task ADD CONSTRAINT fk_task_instruction_item FOREIGN KEY (instruction_item_id) REFERENCES instruction_item(id) ON DELETE SET NULL';
  END IF;
END $$;

-- ---------- evidence ----------
CREATE TABLE IF NOT EXISTS evidence (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES app_group(id),
  project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  case_id uuid REFERENCES "case"(id) ON DELETE SET NULL,
  title varchar(256) NOT NULL,
  file_id uuid REFERENCES file_store(id) ON DELETE SET NULL,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_evidence_group ON evidence(group_id);
CREATE INDEX IF NOT EXISTS idx_evidence_project ON evidence(project_id);
CREATE INDEX IF NOT EXISTS idx_evidence_case ON evidence(case_id);

-- ---------- RLS enable ----------
ALTER TABLE instruction ENABLE ROW LEVEL SECURITY;
ALTER TABLE instruction_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence ENABLE ROW LEVEL SECURITY;

-- ---------- RLS policies: instruction ----------
DROP POLICY IF EXISTS instruction_select_policy ON instruction;
CREATE POLICY instruction_select_policy ON instruction
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR (
    ref_type = 'case'
    AND EXISTS (SELECT 1 FROM case_member cm WHERE cm.case_id = instruction.ref_id AND cm.user_id = app_user_id())
  )
  OR (
    ref_type = 'project'
    AND EXISTS (SELECT 1 FROM project_member pm WHERE pm.project_id = instruction.ref_id AND pm.user_id = app_user_id())
  )
  OR EXISTS (
    SELECT 1 FROM temp_grant tg
    WHERE tg.user_id = app_user_id()
      AND tg.object_type = 'instruction'
      AND tg.object_id = instruction.id
      AND tg.expires_at > now()
  )
);

DROP POLICY IF EXISTS instruction_insert_policy ON instruction;
CREATE POLICY instruction_insert_policy ON instruction
FOR INSERT WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS instruction_update_policy ON instruction;
CREATE POLICY instruction_update_policy ON instruction
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (
    SELECT 1 FROM temp_grant tg
    WHERE tg.user_id = app_user_id()
      AND tg.object_type = 'instruction'
      AND tg.object_id = instruction.id
      AND tg.expires_at > now()
      AND (tg.permission_set->>'can_edit') = 'true'
  )
)
WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS instruction_delete_policy ON instruction;
CREATE POLICY instruction_delete_policy ON instruction
FOR DELETE USING (app_is_admin());

-- ---------- RLS policies: instruction_item (follows instruction) ----------
DROP POLICY IF EXISTS instruction_item_select_policy ON instruction_item;
CREATE POLICY instruction_item_select_policy ON instruction_item
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (SELECT 1 FROM instruction i WHERE i.id = instruction_item.instruction_id)
);

DROP POLICY IF EXISTS instruction_item_insert_policy ON instruction_item;
CREATE POLICY instruction_item_insert_policy ON instruction_item
FOR INSERT WITH CHECK (
  app_can_write_group(group_id)
  AND EXISTS (SELECT 1 FROM instruction i WHERE i.id = instruction_item.instruction_id)
);

DROP POLICY IF EXISTS instruction_item_update_policy ON instruction_item;
CREATE POLICY instruction_item_update_policy ON instruction_item
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR EXISTS (
    SELECT 1 FROM temp_grant tg
    WHERE tg.user_id = app_user_id()
      AND tg.object_type = 'instruction_item'
      AND tg.object_id = instruction_item.id
      AND tg.expires_at > now()
      AND (tg.permission_set->>'can_edit') = 'true'
  )
)
WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS instruction_item_delete_policy ON instruction_item;
CREATE POLICY instruction_item_delete_policy ON instruction_item
FOR DELETE USING (app_is_admin());

-- ---------- RLS policies: evidence ----------
DROP POLICY IF EXISTS evidence_select_policy ON evidence;
CREATE POLICY evidence_select_policy ON evidence
FOR SELECT USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR created_by = app_user_id()
  OR (
    case_id IS NOT NULL
    AND EXISTS (SELECT 1 FROM case_member cm WHERE cm.case_id = evidence.case_id AND cm.user_id = app_user_id())
  )
  OR EXISTS (
    SELECT 1 FROM project_member pm
    WHERE pm.project_id = evidence.project_id AND pm.user_id = app_user_id()
  )
  OR EXISTS (
    SELECT 1 FROM temp_grant tg
    WHERE tg.user_id = app_user_id()
      AND tg.object_type = 'evidence'
      AND tg.object_id = evidence.id
      AND tg.expires_at > now()
  )
);

DROP POLICY IF EXISTS evidence_insert_policy ON evidence;
CREATE POLICY evidence_insert_policy ON evidence
FOR INSERT WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS evidence_update_policy ON evidence;
CREATE POLICY evidence_update_policy ON evidence
FOR UPDATE USING (
  app_is_admin()
  OR group_id = ANY(app_group_ids())
  OR created_by = app_user_id()
  OR EXISTS (
    SELECT 1 FROM temp_grant tg
    WHERE tg.user_id = app_user_id()
      AND tg.object_type = 'evidence'
      AND tg.object_id = evidence.id
      AND tg.expires_at > now()
      AND (tg.permission_set->>'can_edit') = 'true'
  )
)
WITH CHECK (app_can_write_group(group_id));

DROP POLICY IF EXISTS evidence_delete_policy ON evidence;
CREATE POLICY evidence_delete_policy ON evidence
FOR DELETE USING (app_is_admin());

COMMIT;
