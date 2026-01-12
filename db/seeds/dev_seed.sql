-- db/seeds/dev_seed.sql
-- DEV ONLY: local reproducible seed data for API Quickstart.
-- DO NOT run in production.

BEGIN;

-- Fixed IDs for docs/API_QUICKSTART.md
-- Group
--   11111111-1111-1111-1111-111111111111
-- Customer
--   22222222-2222-2222-2222-222222222222
-- Users
--   internal  aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2 (13900000002)
--   client    aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1 (13900000001)
--   external  aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3 (13900000003)
--   dev admin aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9 (kangpu / 13777777392)
-- Project
--   33333333-3333-3333-3333-333333333333
-- Case
--   44444444-4444-4444-4444-444444444444

-- 1) group
INSERT INTO app_group (id, name)
VALUES ('11111111-1111-1111-1111-111111111111'::uuid, '华东战区')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- 2) customer (client binding)
INSERT INTO customer (id, name)
VALUES ('22222222-2222-2222-2222-222222222222'::uuid, '示例客户')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- 3) users
INSERT INTO app_user (id, phone, username, user_type, is_admin, customer_id)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::uuid, '13900000002', 'internal_13900000002', 'internal', true, NULL),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::uuid, '13900000001', 'client_13900000001',   'client',   false, '22222222-2222-2222-2222-222222222222'::uuid),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::uuid, '13900000003', 'external_13900000003', 'external', false, NULL),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9'::uuid, '13777777392', 'kangpu',               'internal', true, NULL)
ON CONFLICT (phone) DO UPDATE
SET username = EXCLUDED.username,
    user_type = EXCLUDED.user_type,
    is_admin = EXCLUDED.is_admin,
    customer_id = EXCLUDED.customer_id;

-- 4) user_group bindings (all to same group)
INSERT INTO user_group (user_id, group_id, role_code)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'member'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'member'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'member'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'admin')
ON CONFLICT (user_id, group_id) DO UPDATE
SET role_code = EXCLUDED.role_code;

-- 5) project (bind group + customer)
INSERT INTO project (id, group_id, customer_id, name, status, created_by, created_at, updated_at)
VALUES (
  '33333333-3333-3333-3333-333333333333'::uuid,
  '11111111-1111-1111-1111-111111111111'::uuid,
  '22222222-2222-2222-2222-222222222222'::uuid,
  '示例项目 Quickstart',
  'ACTIVE',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::uuid,
  now(),
  now()
)
ON CONFLICT (id) DO UPDATE
SET group_id = EXCLUDED.group_id,
    customer_id = EXCLUDED.customer_id,
    name = EXCLUDED.name,
    status = EXCLUDED.status,
    updated_at = now();

-- project members
INSERT INTO project_member (project_id, user_id, member_role)
VALUES
  ('33333333-3333-3333-3333-333333333333'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::uuid, 'member'),
  ('33333333-3333-3333-3333-333333333333'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::uuid, 'member')
ON CONFLICT (project_id, user_id) DO NOTHING;

-- 6) case (bind project + group)
INSERT INTO "case" (id, group_id, project_id, title, status, created_by, created_at, updated_at)
VALUES (
  '44444444-4444-4444-4444-444444444444'::uuid,
  '11111111-1111-1111-1111-111111111111'::uuid,
  '33333333-3333-3333-3333-333333333333'::uuid,
  '示例案件 Quickstart',
  'OPEN',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::uuid,
  now(),
  now()
)
ON CONFLICT (id) DO UPDATE
SET group_id = EXCLUDED.group_id,
    project_id = EXCLUDED.project_id,
    title = EXCLUDED.title,
    status = EXCLUDED.status,
    updated_at = now();

-- case members
INSERT INTO case_member (case_id, user_id, member_role)
VALUES
  ('44444444-4444-4444-4444-444444444444'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2'::uuid, 'assignee'),
  ('44444444-4444-4444-4444-444444444444'::uuid, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3'::uuid, 'assignee')
ON CONFLICT (case_id, user_id) DO NOTHING;

COMMIT;
