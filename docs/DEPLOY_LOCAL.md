# 本地一键启动（V1）

目标：在仓库根目录通过 Docker Compose 一键启动 Postgres/Redis/MinIO，并容器化运行 API + Worker。

> 约束：仓库只提供 `.env.example`，不要提交 `.env`（可能包含敏感信息）。

## 0) 前置条件

- 已安装 Docker Desktop（Windows）并启用 `docker compose`。
- 端口未被占用：`5432`/`6379`/`9000`/`9001`/`8080`。

## 1) 创建本地环境变量文件

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

如需自定义数据库密码、MinIO 账号、bucket 名称等，请编辑 `.env`。

## 2) 一键启动

在仓库根目录执行：

```powershell
docker compose up -d
```

预期：
- `postgres`、`redis`、`minio` 启动并通过 healthcheck。
- `minio-init` 自动创建 bucket（优先使用 `.env` 中 `MINIO_BUCKET`；兼容旧变量 `SECP_S3_BUCKET`）。
- `api` 启动后提供健康检查：`GET http://localhost:8080/health` 返回 `ok`。
- `worker` 启动并开始轮询 outbox（日志无循环报错）。

## 3) 查看日志

```powershell
# API 日志
docker compose logs -f api

# Worker 日志
docker compose logs -f worker

# MinIO 日志
docker compose logs -f minio
```

## 4) 健康检查与访问地址

- API Health：
  - `http://localhost:8080/health`
- MinIO 控制台：
  - `http://localhost:9001`
- MinIO S3 Endpoint（API/Worker 容器内使用）：
  - `http://minio:9000`

## 5) 重启 / 停止 / 清理

```powershell
# 重启某个服务
docker compose restart api

docker compose restart worker

# 停止并删除容器（保留数据卷）
docker compose down

# 停止并删除容器 + 数据卷（会清空 Postgres/MinIO 数据）
docker compose down -v
```

## 6) 常见问题

- API 启动失败（连不上 DB/Redis/MinIO）：
  - 先检查 `docker compose ps` 中各服务是否 healthy
  - 查看日志：`docker compose logs -f api`

- MinIO bucket 未创建：
  - 查看 `minio-init` 日志：`docker compose logs -f minio-init`

- 本地端口冲突：
  - 关闭占用端口的进程，或修改 `docker-compose.yml` 的端口映射。
