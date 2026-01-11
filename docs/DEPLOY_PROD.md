# 生产部署手册（骨架模板；不包含任何真实密钥）

目标：在不提交任何密钥/生产配置的前提下，提供可落地的生产部署骨架与建议。

重要提醒：
- 不要把 `.env`（或任何密钥）提交到 Git。
- 生产密钥应存放在 CI Secret / KMS / Secret Manager 或服务器环境变量中。

## 1. 生产前置条件

你需要准备并可用：
- 外部 Postgres（生产级高可用/备份策略）
- 外部 Redis（生产级高可用/备份策略）
- 外部 S3（AWS S3 或兼容对象存储）
- 域名与 TLS（建议由反向代理 / Ingress 终止 TLS）
- 日志采集（stdout → 日志系统；或容器平台自带采集）

建议（按需）：
- 应用所在网络可访问 Postgres/Redis/S3
- 出站访问短信网关（如未来接入真实短信）
- 监控与告警（HTTP、JVM、DB、Redis、对象存储）

## 2. 配置方式（模板 → 生产 .env）

仓库提供模板：
- [.env.prod.example](../.env.prod.example)

生产环境的做法：
1) 在部署机（或 CI 运行目录）复制模板为 `.env`：

```bash
cp .env.prod.example .env
```

2) 将 `.env` 中的占位符替换为真实值（不要提交）。

说明：
- API/Worker 的 `SPRING_DATASOURCE_URL`/`SPRING_DATA_REDIS_*` 在 compose 中会根据 `SECP_DB_*`/`SECP_REDIS_*` 拼装。
- S3：标准 AWS S3 可不填 `SECP_S3_ENDPOINT`。

## 3. 生产部署建议（api + worker 两个进程）

推荐形态：
- `api`：对外提供 HTTP（8080），并负责 Flyway 迁移
- `worker`：后台消费 outbox/任务队列，不对外暴露端口

Flyway 建议：
- Flyway 只在 `api` 开启（`SPRING_FLYWAY_ENABLED=true`）
- `worker` 禁用 Flyway（`SPRING_FLYWAY_ENABLED=false`）
- 推荐使用独立的 Flyway 账号（具备 DDL 权限），业务账号仅具备 DML 权限

## 4. 使用 docker-compose.prod.yml（推荐骨架）

仓库提供生产 compose 骨架：
- [docker-compose.prod.yml](../docker-compose.prod.yml)

示例启动：

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

说明：
- 该 compose **不包含** postgres/redis/minio（生产使用外部依赖）。
- `api` 映射 `8080:8080`；`worker` 不映射端口。
- Healthcheck 使用容器内 `bash` 进行 TCP 探活（仅做骨架示例）。

## 5. 常见问题

### 5.1 Flyway 迁移失败
可能原因：
- 连接信息错误（DB host/port/name/user/pass）
- Flyway 账号权限不足（需要创建/变更表、索引、函数、RLS 策略等）

排查：
- 查看 `api` 日志：`docker compose -f docker-compose.prod.yml logs --tail=200 api`
- 确认数据库网络连通与凭据正确

### 5.2 RLS 相关（Row Level Security）
现象：查询结果为空/权限不足。

要点：
- 平台使用 Postgres RLS，应用会在事务内通过 session 变量（`app.user_id/app.is_admin/app.group_ids`）驱动可见性。
- 生产环境务必确保迁移（含 RLS 策略）已完整执行。

排查：
- 确认 `api` 的 Flyway 已成功跑完
- 确认应用连接账号具备访问相关表的权限（并不会绕过 RLS）

### 5.3 S3 权限/访问失败
可能原因：
- Bucket 不存在 / region 不匹配
- AccessKey/SecretKey 权限不足（需要读写对象、列举、获取元数据等）
- 对象存储 endpoint 配置错误（非 AWS 兼容存储时更常见）

排查：
- 检查 `SECP_S3_REGION/BUCKET/ACCESS_KEY/SECRET_KEY/ENDPOINT`
- 结合对象存储服务端日志确认拒绝原因（403/Signature mismatch 等）

### 5.4 短信网关
当前版本默认以“验证码写入日志”的方式模拟短信。

生产建议：
- 真实短信接入需要实现 provider，但仓库已预留配置位（见 `.env.prod.example` 的 `SECP_SMS_*`）。

## 6. 安全提醒（必须）

- 不要提交 `.env` / KMS 密钥 / AccessKey / JWT Secret
- CI 中通过 Secret 注入，部署机上通过安全渠道落盘（或直接使用环境变量）
- 定期轮换：DB 密码、Redis 密码（如有）、S3 Key、JWT Secret
