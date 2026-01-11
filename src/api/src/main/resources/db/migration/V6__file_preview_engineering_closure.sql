-- Engineering closure for file preview + watermark (V1)

BEGIN;

-- file_store: status + etag for upload lifecycle + idempotency
ALTER TABLE file_store
  ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'INIT' CHECK (status IN ('INIT','READY'));

ALTER TABLE file_store
  ADD COLUMN IF NOT EXISTS etag varchar(128);

CREATE INDEX IF NOT EXISTS idx_file_status ON file_store(status);

-- token binds variant
ALTER TABLE file_preview_token
  ADD COLUMN IF NOT EXISTS variant varchar(16) NOT NULL DEFAULT 'internal' CHECK (variant IN ('internal','client','external'));

CREATE INDEX IF NOT EXISTS idx_preview_token_variant ON file_preview_token(variant);

-- preview cache index (per-user, per-variant, per-watermark-version)
CREATE TABLE IF NOT EXISTS preview_index (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  file_id uuid NOT NULL REFERENCES file_store(id) ON DELETE CASCADE,
  viewer_user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  variant varchar(16) NOT NULL CHECK (variant IN ('internal','client','external')),
  file_fingerprint varchar(128) NOT NULL,
  wm_ver int NOT NULL,
  s3_key varchar(512) NOT NULL,
  size_bytes bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
  UNIQUE (file_id, viewer_user_id, variant, file_fingerprint, wm_ver)
);

CREATE INDEX IF NOT EXISTS idx_preview_index_lookup ON preview_index(file_id, viewer_user_id, variant, file_fingerprint, wm_ver);
CREATE INDEX IF NOT EXISTS idx_preview_index_expires ON preview_index(expires_at);

ALTER TABLE preview_index ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS preview_index_select_policy ON preview_index;
CREATE POLICY preview_index_select_policy ON preview_index
FOR SELECT USING (
  app_is_admin()
  OR viewer_user_id = app_user_id()
);

DROP POLICY IF EXISTS preview_index_insert_policy ON preview_index;
CREATE POLICY preview_index_insert_policy ON preview_index
FOR INSERT WITH CHECK (
  (app_is_admin() OR viewer_user_id = app_user_id())
  AND EXISTS (SELECT 1 FROM file_store fs WHERE fs.id = preview_index.file_id)
);

DROP POLICY IF EXISTS preview_index_update_policy ON preview_index;
CREATE POLICY preview_index_update_policy ON preview_index
FOR UPDATE USING (
  app_is_admin() OR viewer_user_id = app_user_id()
)
WITH CHECK (
  app_is_admin() OR viewer_user_id = app_user_id()
);

COMMIT;
