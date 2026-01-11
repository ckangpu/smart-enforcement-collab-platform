# 部署常见问题排查（本地 / CI）

> 说明：本文是 [docs/DEPLOY_LOCAL.md](DEPLOY_LOCAL.md) 的“常见问题排查”独立版，便于快速检索。

## 1) minio-init 镜像拉取失败

```powershell
docker compose pull minio-init
docker pull minio/mc:latest
docker compose up -d minio minio-init
docker compose logs --tail=200 minio-init
```

若仍失败：检查网络代理/公司镜像加速器配置（Docker Desktop Settings）。

## 2) /health 连接不上（端口占用 / 容器退出 / Flyway 报错）

```powershell
docker compose ps -a
docker compose logs --tail=200 api
```

- 端口占用：释放 `8080` 或修改 compose 端口映射
- 容器退出：以日志为准定位异常堆栈
- Flyway 报错：查看日志中的 `Flyway`/`Migration` 失败原因

开发环境可直接重置（会清空 DB/MinIO 数据）：

```powershell
docker compose down -v
docker compose up -d
```

## 3) Testcontainers 找不到 Docker（VS Code vs 终端差异）

排查顺序：
1) 确认 Docker Desktop 已启动，并且 `docker info` 在当前终端可用。
2) 若从 VS Code 测试面板触发：重启 VS Code（让它继承最新环境/上下文）。
3) 确认 Docker Context：

```powershell
docker context show
docker info
```

## 4) PowerShell 的 curl 别名问题

PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名；请使用 `curl.exe`：

```powershell
curl.exe http://localhost:8080/health
```

## 5) 迁移报错（开发环境处理）

开发环境推荐直接重置（会清空 DB/MinIO 数据）：

```powershell
docker compose down -v
docker compose up -d
```
