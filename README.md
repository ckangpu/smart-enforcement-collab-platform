# 智慧执行协作平台（V1）

## 1. 项目简介（2~3行）

- 智慧执行协作平台 V1：Postgres RLS + Outbox + 幂等 + client 隔离 + 文件预览水印 + 指令/任务/证据 + 通知/报表。
- 这是一个“V1 baseline + 可持续迭代工程骨架”，用于在不破坏红线的前提下持续演进。

## 2. 快速开始（5 行命令，PowerShell）

> 注意：PowerShell 的 `curl` 是别名（Invoke-WebRequest），请使用 `curl.exe`。

```powershell
Copy-Item .env.dev.example .env
docker compose up -d
docker compose ps
curl.exe -sS -D - http://localhost:8080/health
docker compose logs -f api
```

## 2.1 验收命令（PowerShell 可复制版）

> 注意：PowerShell 的 `curl` 是别名（Invoke-WebRequest），请使用 `curl.exe`。

```powershell
copy .env.dev.example .env
docker compose up -d
powershell -ExecutionPolicy Bypass -File scripts/seed-dev.ps1
curl.exe -sS http://localhost:8080/health
powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1
```

## 2.2 中文化回归检查（可选）

本项目要求“用户可见文案为中文”。可用脚本做常见英文词回归检查：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-ui-chinese.ps1 -VerboseOutput
```

可选 CI（GitHub Actions）片段示例：

```yaml
name: ui-chinese-check
on:
	pull_request:
	push:
		branches: [ main ]

jobs:
	check:
		runs-on: windows-latest
		steps:
			- uses: actions/checkout@v4
			- name: 检查 UI/模板英文词回归
				shell: pwsh
				run: ./scripts/check-ui-chinese.ps1 -VerboseOutput
```

验证 A4 导出（示例，curl.exe 单行）：

```powershell
curl.exe -sS -H "Authorization: Bearer <INTERNAL_TOKEN>" http://localhost:8080/projects/<PROJECT_ID>/a4.pdf -o project_a4.pdf
```

## 最终验收（可复制 PowerShell）

所有用户可见内容必须中文（接口 message、UI 文案、脚本输出）。请先执行中文化检查，再跑单测与 smoke。

UI 验收路径：`/ui/login.html`。
案件入口：在“我的项目”项目表中点击案件编号/名称进入 `case_detail.html`。

```powershell
mvn -f src/api/pom.xml test
mvn -f src/api/src/worker/pom.xml test
pwsh ./scripts/check-ui-chinese.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1 -SkipCompose -DebugLogs
```

## 3. 文档入口（必须用相对路径链接）

- 本地部署：docs/DEPLOY_LOCAL.md
- 常见问题：docs/DEPLOY_TROUBLESHOOTING.md
- 工程红线：docs/ENGINEERING_GUIDE.md
- 水印契约：docs/WATERMARK_CONTRACT.md
- 架构决策：docs/ARCHITECTURE_DECISIONS.md
- Copilot 约束：.github/copilot-instructions.md

## 4. 关键端口与服务（表格或列表）

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| api | 8080 | HTTP API（`/health`） |
| postgres | 5432 | 数据库 |
| redis | 6379 | 缓存/队列 |
| minio | 9000 | S3 endpoint |
| minio (console) | 9001 | 控制台 |

说明：`minio-init` 会在启动时自动创建 bucket（来自 `.env` 的 `MINIO_BUCKET`）。

## 5. 基线与版本（非常简短）

- tag: v1-baseline（V1 基线）
- main 分支持续迭代

## 6. 安全提醒（2 行）

- 不提交 `.env`；只提交 `.env.*.example`。
- 外协只允许 `/preview/**`，禁止 raw download（对外只提供预览版本）。
