-- File preview + watermark V1

BEGIN;

-- Extend file_store to link project
ALTER TABLE file_store
  ADD COLUMN IF NOT EXISTS project_id uuid REFERENCES project(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_file_project ON file_store(project_id);

-- Store generated preview variants
CREATE TABLE IF NOT EXISTS file_variant (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  file_id uuid NOT NULL REFERENCES file_store(id) ON DELETE CASCADE,
  variant varchar(16) NOT NULL CHECK (variant IN ('internal','external')),
  content_type varchar(128) NOT NULL DEFAULT 'application/pdf',
  size_bytes bigint NOT NULL DEFAULT 0,
  s3_key varchar(512) NOT NULL,
  created_by uuid NOT NULL REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (file_id, variant)
);
CREATE INDEX IF NOT EXISTS idx_file_variant_file ON file_variant(file_id);

-- One-time preview token (10 min)
CREATE TABLE IF NOT EXISTS file_preview_token (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  file_id uuid NOT NULL REFERENCES file_store(id) ON DELETE CASCADE,
  viewer_user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  token_sha256 varchar(64) NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (token_sha256)
);
CREATE INDEX IF NOT EXISTS idx_preview_token_viewer ON file_preview_token(viewer_user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_preview_token_file ON file_preview_token(file_id);

-- Enable RLS
ALTER TABLE file_variant ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_preview_token ENABLE ROW LEVEL SECURITY;

-- Policies: file_variant follows file_store visibility
DROP POLICY IF EXISTS file_variant_select_policy ON file_variant;
CREATE POLICY file_variant_select_policy ON file_variant
FOR SELECT USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM file_store fs WHERE fs.id = file_variant.file_id)
);

DROP POLICY IF EXISTS file_variant_insert_policy ON file_variant;
CREATE POLICY file_variant_insert_policy ON file_variant
FOR INSERT WITH CHECK (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM file_store fs WHERE fs.id = file_variant.file_id)
);

DROP POLICY IF EXISTS file_variant_update_policy ON file_variant;
CREATE POLICY file_variant_update_policy ON file_variant
FOR UPDATE USING (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM file_store fs WHERE fs.id = file_variant.file_id)
)
WITH CHECK (
  app_is_admin()
  OR EXISTS (SELECT 1 FROM file_store fs WHERE fs.id = file_variant.file_id)
);

-- Policies: preview token visible to its viewer (or admin)
DROP POLICY IF EXISTS preview_token_select_policy ON file_preview_token;
CREATE POLICY preview_token_select_policy ON file_preview_token
FOR SELECT USING (
  app_is_admin()
  OR viewer_user_id = app_user_id()
);

DROP POLICY IF EXISTS preview_token_insert_policy ON file_preview_token;
CREATE POLICY preview_token_insert_policy ON file_preview_token
FOR INSERT WITH CHECK (
  (app_is_admin() OR viewer_user_id = app_user_id())
  AND EXISTS (SELECT 1 FROM file_store fs WHERE fs.id = file_preview_token.file_id)
);

DROP POLICY IF EXISTS preview_token_update_policy ON file_preview_token;
CREATE POLICY preview_token_update_policy ON file_preview_token
FOR UPDATE USING (
  app_is_admin()
  OR viewer_user_id = app_user_id()
)
WITH CHECK (
  app_is_admin()
  OR viewer_user_id = app_user_id()
);

COMMIT;
