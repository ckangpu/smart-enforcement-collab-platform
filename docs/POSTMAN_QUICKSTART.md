# Postman Quickstart（含 Runner 一键跑通指南）

本指南用于在本地（Docker Compose）跑通 SECP V1 的 Postman Collection，并明确哪些步骤需要人工操作（短信验证码、选择 PDF 文件、预签名 PUT）。

## 最短跑通路径（不看全文也能跑）
- `docker compose up -d` 启动服务
- 运行 `scripts/seed-dev.ps1` 导入 dev seed
- `curl.exe http://localhost:8080/health` 确认 200
- `docker compose logs -f api` 打开日志（用于看短信验证码），按 B 获取 3 类 token
- 按 Runner 顺序跑到 J，看到 `/preview?token=...` 返回 PDF（200）

## Runner 不可用时的最短手动路径
1) 手动发送 `POST /auth/sms/send`（3 次：internal/client/external）
2) 从 logs 取 code，手动填入 `sms_code_*`，再分别发送 `POST /auth/sms/verify`（写入 3 个 token）
3) 手动发送 `POST /instructions` -> `POST /instructions/:id/issue`（写入 `taskId`）
4) 手动发送 `POST /tasks/:taskId/payments`（写入 `paymentId`）
5) 手动跑 Preview：`upload-init` ->（手动 PUT 上传 PDF 或用 curl.exe）-> `upload-complete` -> `create token` -> `GET /preview`

## 约束
- 不要发明接口：本 collection 的路径/字段均来自源码 Controller/DTO。
- 不要在 env 里保存真实 token/验证码：只用于本地 dev。
- client/external 只能访问 `/client/**` 与 `/preview/**`（内部接口会 403，这是正确行为）。

## 1) 运行前准备（必写）
按顺序执行：
1. 启动服务：
    - `docker compose up -d`
2. 导入 dev seed：
    - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/seed-dev.ps1`
3. 确认健康检查 200：
    - `curl.exe http://localhost:8080/health`
4. 打开 API logs（用于看短信验证码）：
    - `docker compose logs -f api`

## 2) 导入与环境选择
1. 导入 Collection：`postman/SECP_V1.postman_collection.json`
2. 导入 Environment：`postman/SECP_local.postman_environment.json`
3. 选择环境为：`SECP local`
4. 检查环境变量（至少确认这些存在且合理）：
    - `baseUrl`（默认 `http://localhost:8080`）
    - `internal_phone` / `client_phone` / `external_phone`
   - `projectId` / `caseId`（将由 Admin Bootstrap 写入，不再依赖 seed 固定值）

说明：你在需求里写的 `phone_internal/client/external` 是“概念名”，本仓库 env 实际 key 为 `internal_phone/client_phone/external_phone`（不改 JSON，只在这里对齐）。

## 3) Runner 推荐执行顺序（必须给出 Folder 顺序）
本 collection 的顶层 folder 结构固定为：
- `A. Health`
- `B. Auth (SMS demo)`
- `C. Admin Bootstrap`
- `D. Internal core`
- `E. Client read + dispute`
- `F. Preview (watermarked)`

你可以用 Runner 分多次跑（推荐），以实现你要求的 A-J 顺序：

### A. Health
- Runner 跑 folder：`A. Health`
   - `GET /health`

### B. Auth - Internal（send/verify）
- Runner 跑 folder：`B. Auth (SMS demo)`
   - 只运行 `POST /auth/sms/send (internal)`
   - 暂停 -> 手动把 `sms_code_internal` 填进环境变量
   - 再运行 `POST /auth/sms/verify (internal) -> internal_token`

### C. Auth - Client
- 同一 folder：`B. Auth (SMS demo)`
   - 只运行 client 的 send
   - 暂停 -> 填 `sms_code_client`
   - 再运行 client 的 verify（写入 `client_token`）

### D. Auth - External
- 同一 folder：`B. Auth (SMS demo)`
   - 只运行 external 的 send
   - 暂停 -> 填 `sms_code_external`
   - 再运行 external 的 verify（写入 `external_token`）

### E. Admin Bootstrap（创建 project/case + 加成员，替代 seed 固定 ID）
- Runner 跑 folder：`C. Admin Bootstrap`
   - `POST /admin/projects -> projectId`
   - `POST /admin/cases -> caseId`
   - `POST /admin/projects/{{projectId}}/members`（把当前 internal 用户加到 project）
   - `POST /admin/cases/{{caseId}}/members`（把当前 internal 用户加到 case）

说明：
- 这一步必须在内部链路（instructions/payment/evidence）之前执行，否则后续请求没有有效的 `projectId/caseId`。

### F. Internal - Instruction Flow（create -> issue -> me/tasks）
- Runner 跑 folder：`D. Internal core`
   - `POST /instructions -> instructionId`
   - `POST /instructions/:id/issue -> taskId`（必须带 `Idempotency-Key`，env 里是 `idem_issue`）
   - `GET /me/tasks -> taskId (fallback)`（兜底：如果 issue 的 `taskIds` 解析失败，可以从列表拿一个 taskId）

### G. Internal - Evidence
- 同一 folder：`D. Internal core`
   - `POST /evidences -> evidenceId`

### H. Internal - Payment & Correct
- 同一 folder：`D. Internal core`
   - 任选其一（建议只选一种，避免产生两笔 payment）：
      - `POST /tasks/:taskId/payments -> paymentId`（推荐，和任务链路一致）
      - 或 `POST /payments -> paymentId (direct)`（直接按 case 记账）
   - `POST /payments/:id/correct?reason=... -> correctedPaymentId`
   - `GET /reports/zone-dashboard`（验收内部报表）

### I. Client - Payments & Disputes & Complaints
- Runner 跑 folder：`E. Client read + dispute`
   - `GET /client/projects`
   - `GET /client/projects/:projectId/payments?caseId=... -> paymentId`
   - `POST /client/payments/:paymentId/disputes -> disputeId`
   - `GET /client/complaints`

### J. Preview - Upload & Token & Fetch
- Runner 跑 folder：`F. Preview (watermarked)`
   - `POST /files/upload-init -> presignedPutUrl,fileId,s3KeyRaw`（internal only）
   - `PUT {{presignedPutUrl}} (upload PDF bytes)`（见下方“必须手动点”）
   - `POST /files/upload-complete`
   - `POST /preview/files/:fileId/tokens -> previewToken`
   - `GET /preview?token=... (PDF bytes)`

## 4) Runner 里“必须手动”的点（非常关键）

### 4.1 短信验证码（必须手动）
验证码来自 API logs。打开：
- `docker compose logs -f api`

找到类似这一行：
- `[sms] phone=13900000002 code=123456`

把 code 填入环境变量（Runner 运行前或暂停时都可以）：
- `sms_code_internal`（对应 `internal_phone`）
- `sms_code_client`（对应 `client_phone`）
- `sms_code_external`（对应 `external_phone`）

### 4.2 预览上传（PUT 预签名 URL）
collection 已包含 `PUT {{presignedPutUrl}}` 请求，但 Postman Runner 通常**无法在 Runner 模式下选择本地文件**。

推荐做法：
1) 用 Runner 先跑到 `POST /files/upload-init`，确保 env 里已写入：
    - `presignedPutUrl`
    - `fileId`
    - `s3KeyRaw`
2) 然后**退出 Runner**，手动点击发送 `PUT {{presignedPutUrl}}`：
    - Body 选择 File/Binary
    - 选择任意小的本地 PDF 文件
3) 再回到 Runner，继续跑 `upload-complete` / `create token` / `GET /preview`

如果 Postman 无法对动态 URL 发 PUT（或你更想用命令行），用 `curl.exe` 作为替代：

```powershell
# Windows PowerShell 中务必用 curl.exe（避免 Invoke-WebRequest 别名）
$url = $env:PRESIGNED_PUT_URL  # 或者直接把 env 里的 presignedPutUrl 复制到这里
curl.exe -X PUT -H "Content-Type: application/pdf" --data-binary "@C:\\path\\to\\sample.pdf" "$url"
```

说明：
- `upload-init` 返回的 `fileId/s3KeyRaw/presignedPutUrl` 仍然以 Postman env 为准；你用 curl 上传完后，直接继续调用 `POST /files/upload-complete` 即可。

## 5) 成功判定（验收标准）
跑完上述 A-J 后，至少满足：
- `internal_token` / `client_token` / `external_token` 均已写入环境变量
- issue 后 `taskId` 已写入环境变量（来自 issue 的 `taskIds[0]` 或 `GET /me/tasks` 兜底）
- `paymentId` 已写入环境变量；执行 correct 后 `correctedPaymentId`（或响应中的 `newPaymentId`）已写入
- `GET /client/projects/:projectId/payments` 响应里包含 `paymentId`（且 env 里会被写入）
- `GET /preview?token=...` 返回 200，响应 `Content-Type` 为 `application/pdf`，可在 Postman 里预览/下载
- `GET /reports/zone-dashboard` 返回 200，且每行对象包含 `instruction` / `overdue` / `task` / `payment` 分组字段

## 6) 常见问题（8 条以内）
1. 401：token 为空/过期，或忘了切到 `SECP local` 环境。
2. 403：client/external 调用了 internal API（如 `/files/*`、`/payments`），这是正确的访问控制。
3. 404：RLS 不泄露存在性（你没权限时会像“资源不存在”）。
4. 422：对没有 case 的任务创建 payment 会报 `TASK_HAS_NO_CASE`（这是正确行为）。
5. 预签名 PUT 403 SignatureDoesNotMatch：常见原因是 URL 过期、复制时引号/转义破坏了 URL、或 Content-Type 不一致。
6. preview token 404：token 过期/已使用（一次性）/用户不匹配（external token 只能用 external 视角消费）。
7. Runner 跑不动文件上传：这段请手动点请求发送，或用 `curl.exe` 替代。
8. Windows PowerShell `curl` 是别名：请用 `curl.exe`。
