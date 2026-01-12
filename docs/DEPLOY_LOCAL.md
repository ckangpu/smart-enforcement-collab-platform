# 本地交付手册：Windows 一键启动（可复制执行）

目标：在 Windows 本机通过 Docker Compose 一键启动：Postgres / Redis / MinIO / API / Worker。

重要约束：仓库只提供示例环境文件，不要提交 `.env`（可能包含敏感信息）。

## 0. 前置条件（Windows + Docker Desktop + WSL2 + Maven + JDK21）

必备：
- Windows 10/11
- Docker Desktop（启用 WSL2 后端）
- WSL2 已安装并可用
- JDK 21
- Maven 3.9+

建议你先在 PowerShell 验证：

```powershell
docker version
docker compose version
wsl -l -v
java -version
mvn -v
```

端口要求（默认映射）：
- `api`：`8080`
- `minio`：`9000`（S3 endpoint）、`9001`（Console）
- `postgres`：`5432`
- `redis`：`6379`

## 1. 从零开始（git clone → 进入目录）

```powershell
git clone <REPO_URL>
cd smart-enforcement-collab-platform
```

## 2. 配置（复制 .env.example → .env；解释变量含义；哪些必须改）

1) 复制示例文件：

```powershell
Copy-Item .env.example .env
```

2) 编辑 `.env`：

```powershell
notepad .env
```

### 2.1 变量说明（按默认 compose/应用读取）

说明：以下变量均可放在 `.env` 里；不要把真实密钥提交到 Git。

**Postgres（容器）**
- `POSTGRES_DB`：数据库名（本地默认 `secp`）
- `POSTGRES_USER`：DB 超级用户（用于 Flyway 初始化/迁移）
- `POSTGRES_PASSWORD`：DB 超级用户密码

**应用 DB 用户（由迁移创建/用于 API&Worker 业务连接）**
- `DB_APP_USER`：应用连接账号
- `DB_APP_PASSWORD`：应用连接密码

**Redis（容器）**
- 无强制变量；默认端口 `6379`

**MinIO（本地 S3）**
- `MINIO_ROOT_USER`：MinIO 控制台登录用户名
- `MINIO_ROOT_PASSWORD`：MinIO 控制台登录密码
- `MINIO_BUCKET`：启动时由 `minio-init` 自动创建的 bucket（本地推荐 `secp-dev`）

**应用存储（S3 客户端）**
- `SECP_S3_ENDPOINT`：S3 endpoint（本地 compose 内固定为 `http://minio:9000`）
- `SECP_S3_BUCKET`：应用读写的 bucket（建议与 `MINIO_BUCKET` 一致）
- `SECP_S3_REGION`：region（本地可用 `us-east-1`）
- `SECP_S3_ACCESS_KEY`：access key（本地可填 MinIO root user）
- `SECP_S3_SECRET_KEY`：secret key（本地可填 MinIO root password）

**鉴权（开发用）**
- `SECP_JWT_SECRET`：JWT 签名密钥（仅开发环境；生产必须更换）

### 2.2 哪些必须改？

本地开发（仅跑起来）：
- 允许保持示例值，但至少建议修改 `SECP_JWT_SECRET`。

生产/类生产：
- 必须设置强密码并更换所有密钥类变量（不要使用示例值）。

提示：本仓库还提供了 dev/prod 分离骨架文件（不提交 `.env`）：
- `.env.dev.example`
- `.env.prod.example`

## 3. 一键启动（docker compose up -d；docker compose ps）

在仓库根目录执行：

```powershell
docker compose up -d
docker compose ps
```

预期：
- `postgres`、`redis`、`minio` 为 `healthy`
- `minio-init` 运行完退出（`Exited (0)`）
- `minio-init` 会自动创建 bucket（优先使用 `.env` 的 `MINIO_BUCKET`；兼容旧变量 `SECP_S3_BUCKET`）
- `api`、`worker` 为 `Up`

## 4. 健康检查（用 curl.exe；/health）

注意：Windows PowerShell 里的 `curl` 是别名（`Invoke-WebRequest`），请使用 `curl.exe`。

```powershell
curl.exe -sS -D - http://localhost:8080/health
```

预期输出：HTTP 200 且 body 为 `ok`。

## 4.1 导入 dev seed（可选但推荐；仅本地开发/演示）

用于让非开发也能按 Quickstart 直接跑通登录、项目/案件查询、外协预览等链路。

```powershell
./scripts/seed-dev.ps1
```

注意：
- 这会直接向本地 Postgres 写入示例数据。
- **不要在生产/类生产环境运行**。

## 4.1.1 DEV 管理员账号（kangpu）

DEV ONLY：用于本地开发/演示的最高权限 internal 管理员账号。

执行顺序：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/seed-dev.ps1
powershell -ExecutionPolicy Bypass -File scripts/set-password-dev.ps1
```

然后在 UI 登录：
- http://localhost:8080/ui/login.html → 选择“密码登录（internal）”
- username：`kangpu`（或 `13777777392`）
- password：`800810`

## 4.2 一键验收（DEV ONLY）

脚本会按顺序执行：启动（含 `--build`）→ `/health` 轮询 → 导入 dev seed → 三类用户登录（自动解析短信验证码；解析不到会提示手动输入）→ 调用报表接口并断言关键字段，最后输出 `SMOKE PASS`。

注意：
- 仓库不会提交 `.env`；脚本会提示你从 `.env.dev.example` 生成 `.env`（仅本地开发/演示）。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1
```

## 5. 查看日志（docker compose logs -f api / worker / minio / postgres）

```powershell
docker compose logs -f api
docker compose logs -f worker
docker compose logs -f minio
docker compose logs -f postgres
```

## 6. 常见问题排查（必须包含）

### a) minio-init 镜像拉取失败怎么办

```powershell
docker compose pull minio-init
docker pull minio/mc:latest
docker compose up -d minio minio-init
docker compose logs --tail=200 minio-init
```

若仍失败：检查网络代理/公司镜像加速器配置（Docker Desktop Settings）。

### b) /health 连接不上怎么办（端口占用、容器退出、flyway 报错）

1) 先看容器状态：

```powershell
docker compose ps -a
```

2) 若 `api` 不是 `Up`：

```powershell
docker compose logs --tail=200 api
```

常见原因：
- 端口 `8080` 被占用：修改占用进程或改 compose 端口映射
- `api` 容器退出：查看日志定位异常
- Flyway 迁移报错：查看日志中 `Flyway`/`Migration` 相关错误

### c) Testcontainers 找不到 Docker 怎么办（VS Code vs 终端差异）

现象：`mvn test` 中 Testcontainers 提示找不到 Docker。

排查顺序：
1) 确认 Docker Desktop 已启动，并且 `docker info` 在当前终端可用。
2) 如果你是从 VS Code 的测试面板/Java Test Runner 触发：重启 VS Code（让它继承最新环境/上下文）。
3) 确认 Docker Context 是 `desktop-linux`：

```powershell
docker context show
```

### d) Windows PowerShell curl 别名问题（必须写 curl.exe）

PowerShell 里：
- `curl` 是 `Invoke-WebRequest` 的别名
- 需要用：`curl.exe`

示例：

```powershell
curl.exe http://localhost:8080/health
```

### e) 迁移报错怎么处理（开发环境：docker compose down -v 重建）

开发环境推荐直接重置（会清空 DB/MinIO 数据）：

```powershell
docker compose down -v
docker compose up -d
```

## 7. 清理与重置（docker compose down -v；如何删除 volume）

```powershell
# 停止并删除容器（保留数据卷）
docker compose down

# 停止并删除容器 + 数据卷（会清空 Postgres/MinIO 数据）
docker compose down -v

# 查看并手动删除卷（谨慎）
docker volume ls
```

## 8. 验收 checklist（可复制版）

```powershell
# 1) 启动
docker compose up -d
docker compose ps

# 2) API 健康检查（必须用 curl.exe）
curl.exe http://localhost:8080/health

# 3) 测试
mvn -f src/api/pom.xml test
mvn -f src/api/src/worker/pom.xml test
```

最后提醒：运行健康检查请使用 PowerShell 的 `curl.exe`。
