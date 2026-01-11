# 智慧执行协作平台（V1）

本项目为执行法律服务团队的一体化协作平台，核心能力：
- 战区（group_id）硬隔离：Postgres RLS
- 模块化单体 + Worker：快速迭代且可拆分
- Outbox 事件总线：通知/预警/审计/预览生成
- 文件在线预览 + 水印：外协强水印（满屏斜向）+ 图像化PDF（不可复制文本）
- 回款明细对客户可见：必须具备更正链与审计
- 认证：短信登录 + 用户名

## 关键文档（必须先读）
- docs/ENGINEERING_GUIDE.md（工程指导与红线）
- docs/RLS_POLICIES.sql（RLS 策略示例）
- docs/OUTBOX_IDEMPOTENCY.md（幂等与重试）
- docs/FILE_PREVIEW_WATERMARK.md（预览/水印实现）
- .github/copilot-instructions.md（Copilot 约束）

## 红线（任何代码不得违反）
- 严禁绕过 RLS：所有数据访问必须依赖 app.* session 变量
- 严禁直接 UPDATE payment 核心字段（金额/日期/渠道等），必须走更正链（新记录）
- 外协永远禁下载/禁导出/禁解密
- 客户端 API 字段白名单输出，禁止复用内部 DTO
- 写操作必须写 audit_log，并写 outbox（同一事务）

## 建议目录结构（模块化单体）
- src/modules/identity_access
- src/modules/lead_crm
- src/modules/evaluation
- src/modules/project_case
- src/modules/intel_instruction
- src/modules/execution_ops
- src/modules/dispute
- src/modules/service_cs
- src/modules/file_service
- src/modules/audit_compliance
- src/modules/notification
- src/infra (db/redis/s3/outbox/preview)
