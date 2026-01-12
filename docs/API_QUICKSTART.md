# API Quickstart（Windows PowerShell + curl.exe）

> 目标：在本地环境用最少步骤跑通 **internal / client / external（外协预览）** 的关键接口链路。
>
> 约定：本手册以 Windows PowerShell 为主，所有示例必须使用 `curl.exe`（避免 PowerShell 的 `curl` 别名）。
>
> 默认 API：`http://localhost:8080`（与 docs/DEPLOY_LOCAL.md 一致）。

---

## 1) 前置条件

1. 已按 docs/DEPLOY_LOCAL.md 启动 docker compose。
2. 导入本地可复现 dev seed（只用于本地开发/演示；不要用于生产）：

```powershell
./scripts/seed-dev.ps1
```

- 期望：命令成功结束，输出 `Done. Dev seed imported.`

- dev seed 说明（本手册只依赖它创建 **用户/组/客户绑定**）：
   - `GROUP_ID`：`11111111-1111-1111-1111-111111111111`（后续用 /admin bootstrap 创建项目/案件）
   - internal/client/external 三类手机号：见下文登录章节

3. 确认健康检查可用：

```powershell
curl.exe -sS http://localhost:8080/health
```

- 期望：HTTP 200
- 关键返回：纯文本 `ok`

4. 需要准备 3 类用户：
   - internal 用户（内部，`user_type=internal`）用于调用内部接口（非 `/client/**`、非 `/preview/**`）。
   - client 用户（客户，`user_type=client`）用于调用 `/client/**`。
   - external 用户（外协，`user_type=external`）用于调用 `/preview/**` 的 external 预览。

> 说明：V1 没有“创建用户/项目/案件”的公开接口。本手册默认这些数据来自 **测试用例 seed/数据库 seed**。
>
> - 可参考集成测试的 seed 逻辑（它们会通过 JDBC 直接插入 `app_user/project/case` 等数据）：
>   - `src/api/src/test/java/com/secp/api/it/InstructionTaskEvidenceIT.java`
>   - `src/api/src/test/java/com/secp/api/it/ClientApiIsolationIT.java`
>   - `src/api/src/test/java/com/secp/api/it/FilePreviewWatermarkIT.java`

5. 安全提醒：
   - 本手册不写任何敏感密钥/口令/真实 token。
   - `.env` 不可提交到仓库（仅用 `.env.*.example` 作为骨架）。

---

## 2) 环境变量（手册中用到的 PowerShell 变量）

> 说明：为了命令可复制，本文统一用 PowerShell 变量保存 Base URL、Content-Type 和 3 类 token。
>
> 若接口返回字段名与示例不同，可按实际返回调整（本文示例不依赖 jq）。

```powershell
$Base = "http://localhost:8080"
$Json = "application/json"

# 下面三类 token 都是 JWT：从登录接口返回后“人工复制粘贴”即可
$InternalToken = "<PASTE_INTERNAL_JWT_HERE>"
$ClientToken   = "<PASTE_CLIENT_JWT_HERE>"
$ExternalToken = "<PASTE_EXTERNAL_JWT_HERE>"
```

---

## 3) 登录（JWT 获取）

本系统使用短信 demo + username（手机号）方式登录：

- `POST /auth/sms/send`
- `POST /auth/sms/verify`

> demo 模式验证码会打印在 api logs：
>
> ```powershell
> docker compose logs -f api
> ```
>
> 你会看到类似输出：`[sms] phone=13900000002 code=123456`

### 3.1 internal 登录（获取 $InternalToken）

1) 发送验证码：

```powershell
curl.exe -sS -X POST "$Base/auth/sms/send" -H "Content-Type: $Json" -d '{"phone":"13900000002"}'
```

- 期望：HTTP 200
- 关键返回：`ok`（示例：`{"ok":true}`）

2) 校验验证码并获取 token：

```powershell
curl.exe -sS -X POST "$Base/auth/sms/verify" -H "Content-Type: $Json" -d '{"phone":"13900000002","code":"<PASTE_CODE_FROM_LOGS>"}'
```

- 期望：HTTP 200
- 关键返回：`token`（示例：`{"token":"<JWT>"}`）

把返回的 `token` 人工复制到 `$InternalToken`。

### 3.x 密码登录（可选，仅 internal）

新增接口：
- `POST /auth/password/login`（仅允许 `user_type=internal`，否则 403）

注意：首次使用前需要 **admin** 给该 internal 用户设置密码：
- `POST /admin/users/{userId}/password`

> 安全提醒：以下示例仅用于本地开发/演示；不要在生产环境使用弱密码。

dev seed 下 internal 用户固定为 admin：

```powershell
$InternalUserId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
```

1) 先用短信登录拿到 `$InternalToken`（见 3.1），然后设置密码：

```powershell
curl.exe -sS -X POST "$Base/admin/users/$InternalUserId/password" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d '{"password":"admin123"}'
```

2) 用用户名+密码登录拿 token（这一步不需要 Authorization header）：

```powershell
curl.exe -sS -X POST "$Base/auth/password/login" -H "Content-Type: $Json" -d '{"username":"internal_13900000002","password":"admin123"}'
```

- 期望：HTTP 200
- 关键返回：`token`（示例：`{"token":"<JWT>"}`）

### 3.2 client 登录（获取 $ClientToken）

> 重要：`/auth/**` 路径本身不区分 internal/client。
> token 的“用户类型”（`user_type`）取决于数据库里该手机号对应的 `app_user.user_type`。

1) 发送验证码：

```powershell
curl.exe -sS -X POST "$Base/auth/sms/send" -H "Content-Type: $Json" -d '{"phone":"13900000001"}'
```

- 期望：HTTP 200
- 关键返回：`ok`（示例：`{"ok":true}`）

2) 校验验证码并获取 token：

```powershell
curl.exe -sS -X POST "$Base/auth/sms/verify" -H "Content-Type: $Json" -d '{"phone":"13900000001","code":"<PASTE_CODE_FROM_LOGS>"}'
```

- 期望：HTTP 200
- 关键返回：`token`（示例：`{"token":"<JWT>"}`）

把返回的 `token` 人工复制到 `$ClientToken`。

> external（外协）用户同理：用外协手机号走相同登录流程，拿到 `$ExternalToken`。

- dev seed 里 external 手机号固定为：`13900000003`

---

## 4) internal 端：最小闭环（先 bootstrap 项目/案件，再指令 -> 任务 -> 证据 -> 回款/更正 -> 结案可选）

> 认证：internal API（除 `/auth/**`、`/client/**`、`/preview/**` 外）需要 `Authorization: Bearer <JWT>`。
>
> client/external 访问内部 API 会被拦截为 403（InternalApiGuardFilter）。

### 4.0 先用 Admin Bootstrap 创建 projectId / caseId（不依赖 seed 固定项目）

> internal-only，受 `InternalApiGuardFilter` 保护。
>
> 规则要点：
> - 非 admin internal：只能在自己 `app.group_ids` 内创建（否则 404）
> - 写操作同事务：业务表 + `audit_log` + `event_outbox`

准备变量：

```powershell
$GroupId = "11111111-1111-1111-1111-111111111111"
```

1) 创建项目 `POST /admin/projects`：

> 编号与受理日期：
> - `acceptedAt`：受理日期（`YYYY-MM-DD`）。用于确定业务编号中的 `YYYYMM`。
> - `codeSource`：编号来源，`AUTO` 自动生成，`MANUAL` 手动指定。
> - `code`：手动编号（仅 `codeSource=MANUAL` 时传）。格式：`^[A-Z]{1,8}\d{6}\d{4}$`。
> - 编号冲突：HTTP 409，中文提示“编号已存在，请更换。”
> - 编号格式不合法：HTTP 422，中文提示“编号格式不正确。”

```powershell
$Resp = curl.exe -sS -X POST "$Base/admin/projects" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"groupId\":\"$GroupId\",\"name\":\"Quickstart 项目\",\"bizTags\":[\"quickstart\"],\"acceptedAt\":\"2026-01-01\",\"codeSource\":\"AUTO\"}"
$ProjectId = ($Resp | ConvertFrom-Json).projectId
```

2) 创建案件 `POST /admin/cases`（groupId 会继承 project.group_id）：

```powershell
$Resp = curl.exe -sS -X POST "$Base/admin/cases" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"projectId\":\"$ProjectId\",\"title\":\"Quickstart 案件\",\"acceptedAt\":\"2026-01-01\",\"codeSource\":\"AUTO\"}"
$CaseId = ($Resp | ConvertFrom-Json).caseId
```

3) 可选：把自己加入成员（重复添加不报错）。dev seed 下 internal 用户 id 固定为：`aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2`

```powershell
$InternalUserId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"

curl.exe -sS -X POST "$Base/admin/projects/$ProjectId/members" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"userId\":\"$InternalUserId\",\"role\":\"member\"}"
curl.exe -sS -X POST "$Base/admin/cases/$CaseId/members"     -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"userId\":\"$InternalUserId\",\"role\":\"assignee\"}"
```

### 4.0.1 项目详情（执行律师工作台） `GET /projects/{projectId}/detail`

说明：该接口为 **internal-only**（外协 / client 不可访问）。不可见统一返回 404。

PowerShell 单行示例：

```powershell
curl.exe -sS -H "Authorization: Bearer $InternalToken" "$Base/projects/$ProjectId/detail"
```

### 4.0.2 A4 PDF 打印导出 `GET /projects/{projectId}/a4.pdf`

返回：
- `Content-Type: application/pdf`
- `Content-Disposition: inline; filename=Project_<projectId>.pdf`

PowerShell 单行示例（保存到本地文件）：

```powershell
curl.exe -sS -H "Authorization: Bearer $InternalToken" -o "project_$ProjectId.pdf" "$Base/projects/$ProjectId/a4.pdf"
```

### 4.0.3 案件详情 `GET /cases/{caseId}/detail`

说明：该接口为 **internal-only**。不可见统一返回 404。

PowerShell 单行示例：

```powershell
curl.exe -sS -H "Authorization: Bearer $InternalToken" "$Base/cases/$CaseId/detail"
```

### 4.0.4 案件 A4 PDF 打印导出 `GET /cases/{caseId}/a4.pdf`

返回：
- `Content-Type: application/pdf`
- `Content-Disposition: inline; filename=Case_<caseId>.pdf`

说明：导出会写入审计日志（`audit_log.action=case_a4_export`）。

PowerShell 单行示例（保存到本地文件）：

```powershell
curl.exe -sS -H "Authorization: Bearer $InternalToken" -o "case_$CaseId.pdf" "$Base/cases/$CaseId/a4.pdf"
```

### 4.1 创建指令（草稿） `POST /instructions`

接口：创建一条 DRAFT 指令，返回 `instructionId`。

#### 示例 A：refType=project（project-only 流程）

> 你需要一个已存在的 `projectId`（来自测试 seed/数据库数据）。

```powershell
curl.exe -sS -X POST "$Base/instructions" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"refType\":\"project\",\"refId\":\"$ProjectId\",\"title\":\"Instr (project)\",\"items\":[{\"title\":\"item-1\",\"dueAt\":\"2026-01-11T12:00:00+08:00\"}]}"
```

- 期望：HTTP 201
- 关键返回：`instructionId`

> 重点：当 refType=project 时，后续 `issue` 可以不传 `targetCaseId`（或传 null），会生成 **project-only task**（其 `caseId` 可能为 null）。

#### 示例 B：refType=case（绑定案件）

> 你需要一个已存在的 `caseId`（且该 case 必须存在于数据库中）。

```powershell
curl.exe -sS -X POST "$Base/instructions" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"refType\":\"case\",\"refId\":\"$CaseId\",\"title\":\"Instr (case)\",\"items\":[{\"title\":\"item-1\",\"dueAt\":\"2026-01-11T12:00:00+08:00\"}]}"
```

- 期望：HTTP 201
- 关键返回：`instructionId`

### 4.2 下发指令 `POST /instructions/{instructionId}/issue`（必须包含 Idempotency-Key）

- Header：`Idempotency-Key` **必填**
- Body：可选。refType=project 时可传 `{"targetCaseId":null}` 生成 project-only task。

生成 Idempotency-Key（PowerShell GUID）：

```powershell
$Idem = [guid]::NewGuid().ToString()
```

下发（project-only 示例）：

```powershell
curl.exe -sS -X POST "$Base/instructions/<INSTRUCTION_ID>/issue" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -H "Idempotency-Key: $Idem" -d '{"targetCaseId":null}'
```

- 期望：HTTP 200
- 关键返回：`instructionId` / `version` / `taskIds`（数组）

**重放验证（不应重复创建 task）**：用同一个 `$Idem` 再调用一次，应返回完全相同的响应体（至少 `taskIds` 一致）。

```powershell
curl.exe -sS -X POST "$Base/instructions/<INSTRUCTION_ID>/issue" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -H "Idempotency-Key: $Idem" -d '{"targetCaseId":null}'
```

- 期望：HTTP 200（replay）
- 关键返回：与第一次一致（尤其是 `taskIds`）

### 4.3 查询我的任务 `GET /me/tasks`

```powershell
curl.exe -sS -X GET "$Base/me/tasks" -H "Authorization: Bearer $InternalToken"
```

- 期望：HTTP 200
- 关键返回：数组；每项包含 `taskId` / `projectId` / `caseId`（project-only task 的 `caseId` 可能为 null）

### 4.4 上传证据 `POST /evidences`

> 已核对：接口真实路径为 `/evidences`（`EvidenceController` 的 `@RequestMapping("/evidences")`）。

project-only evidence 示例（`caseId=null`，`projectId` 必填）：

```powershell
curl.exe -sS -X POST "$Base/evidences" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"projectId\":\"$ProjectId\",\"caseId\":null,\"title\":\"Evidence (project-only)\",\"fileId\":null}"
```

- 期望：HTTP 201
- 关键返回：`evidenceId`

### 4.5 回款（绑定 case 的红线说明）

红线：**payment 必须绑定 case**。

- 对 project-only task（`caseId=null`）创建回款应返回 422。
- 对绑定了 case 的任务/案件才允许创建 payment。

#### 4.5.1 project-only task 试图创建回款（应返回 422）

接口：`POST /tasks/{taskId}/payments`

```powershell
curl.exe -sS -X POST "$Base/tasks/<PROJECT_ONLY_TASK_ID>/payments" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d '{"amount":12.34,"paidAt":"2026-01-11T12:00:00+08:00","payChannel":"BANK","payerName":"payer","bankLast4":"1234","clientNote":"client-note","internalNote":"internal-note","isClientVisible":true}'
```

- 期望：HTTP 422
- 关键返回：`error=UNPROCESSABLE_ENTITY`，并包含 `reason`（示例 reason：`TASK_HAS_NO_CASE`）

#### 4.5.2 绑定 case 的 payment create 示例（使用已存在接口，不发明）

你可以选择两种**已存在**的内部入口：

A) 按任务创建（用于闭环演示）：`POST /tasks/{taskId}/payments`

```powershell
curl.exe -sS -X POST "$Base/tasks/<CASE_TASK_ID>/payments" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d '{"amount":100.00,"paidAt":"2026-01-11T12:00:00+08:00","payChannel":"BANK","payerName":"payerA","bankLast4":"1234","clientNote":"client-note","internalNote":"internal-note","isClientVisible":true}'
```

- 期望：HTTP 200
- 关键返回：`paymentId`

B) 直接按 projectId+caseId 创建：`POST /payments`（可选 `Idempotency-Key`）

```powershell
$IdemPay = [guid]::NewGuid().ToString(); curl.exe -sS -X POST "$Base/payments" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -H "Idempotency-Key: $IdemPay" -d "{\"projectId\":\"$ProjectId\",\"caseId\":\"$CaseId\",\"amount\":100.00,\"paidAt\":\"2026-01-11T12:00:00+08:00\",\"payChannel\":\"BANK\",\"payerName\":\"payerA\",\"bankLast4\":\"1234\",\"clientNote\":\"client-note\",\"internalNote\":\"internal-note\",\"isClientVisible\":true}"
```

- 期望：HTTP 200
- 关键返回：`paymentId`

#### 4.5.3 payment 更正示例（corrected_from_payment_id）

接口：`POST /payments/{paymentId}/correct?reason=...`（可选 `Idempotency-Key`）

```powershell
$Idem2 = [guid]::NewGuid().ToString(); curl.exe -sS -X POST "$Base/payments/<PAYMENT_ID>/correct?reason=fix" -H "Authorization: Bearer $InternalToken" -H "Idempotency-Key: $Idem2"
```

- 期望：HTTP 200
- 关键返回：`newPaymentId`

> 说明：更正不是 UPDATE 原 payment 核心字段，而是新增一条 payment，并用 `corrected_from_payment_id` 关联旧记录。

---

## 5) client 端：查看回款明细 + 发起对账疑问（dispute/complaint）

> 认证：client API 仅允许 `user_type=client` 的 token 访问（`ClientAuthContext.requireClient()`）。

### 5.1 列出项目 `GET /client/projects`

```powershell
curl.exe -sS -X GET "$Base/client/projects" -H "Authorization: Bearer $ClientToken"
```

- 期望：HTTP 200
- 关键返回：数组；每项至少包含 `id`（projectId）

### 5.2 查看回款明细 `GET /client/projects/{projectId}/payments`

- 可选 query 参数：`caseId`（如果你希望只看某个 case 的回款）。

```powershell
curl.exe -sS -X GET "$Base/client/projects/$ProjectId/payments" -H "Authorization: Bearer $ClientToken"
```

- 期望：HTTP 200
- 关键返回：数组；每项包含 `paymentId` / `paidAt` / `amount` / `payChannel` / `payerName` / `bankLast4` / `clientNote` / `correctedFlag`

如需带 caseId：

```powershell
curl.exe -sS -X GET "$Base/client/projects/$ProjectId/payments?caseId=$CaseId" -H "Authorization: Bearer $ClientToken"
```

- 期望：HTTP 200
- 关键返回：同上

强调：
- 只返回“有效 payment”（如果某条 payment 被更正了，旧记录不会出现在列表里）。
- 只返回 `is_client_visible=true` 的 payment。

### 5.3 发起对账疑问 `POST /client/payments/{paymentId}/disputes`

> 建议带 `Idempotency-Key`，避免重复提交。

```powershell
$Idem3 = [guid]::NewGuid().ToString(); curl.exe -sS -X POST "$Base/client/payments/<PAYMENT_ID>/disputes" -H "Authorization: Bearer $ClientToken" -H "Content-Type: $Json" -H "Idempotency-Key: $Idem3" -d '{"title":"对账争议","message":"请核对金额"}'
```

- 期望：HTTP 201
- 关键返回：`disputeId` / `status`（示例：`OPEN`）

### 5.4 查询投诉/对账记录 `GET /client/complaints`

```powershell
curl.exe -sS -X GET "$Base/client/complaints" -H "Authorization: Bearer $ClientToken"
```

- 期望：HTTP 200
- 关键返回：数组；每项包含 `id` / `projectId` / `paymentId` / `status` / `title` / `message` / `createdAt`

字段白名单说明：
- client 侧只会返回必要字段（例如 `id/projectId/paymentId/status/title/message/createdAt`）。
- 不会返回 `customerId/type/slaDueAt` 等敏感/内部字段。

---

## 6) external（外协）预览：强水印 image-based PDF

> 预览链路分两段：
>
> 1) internal 上传 raw 文件（`/files/upload-init` -> presigned PUT -> `/files/upload-complete`）
> 2) external 创建预览 token 并查看 `/preview`（external 预览应为图像化 PDF，不可复制文本，含 EXTERNAL 水印）

### 6.1 上传文件（internal）

#### 6.1.1 upload-init：获取 presigned PUT URL

```powershell
curl.exe -sS -X POST "$Base/files/upload-init" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"caseId\":\"$CaseId\",\"filename\":\"doc.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":123}"
```

- 期望：HTTP 200
- 关键返回：`fileId` / `s3KeyRaw` / `presignedPutUrl` / `expiresAt`

#### 6.1.2 presigned PUT：把 PDF 上传到对象存储

> 说明：presigned URL 通常很长，建议整段复制。
>
> 若你在 PowerShell 里遇到 URL 转义/换行问题，可改用 Postman 按同样的 PUT 方式上传。

```powershell
curl.exe -sS -X PUT "<PRESIGNED_PUT_URL>" -H "Content-Type: application/pdf" --data-binary "@.\doc.pdf"
```

- 期望：HTTP 200 或 204（取决于 MinIO/S3 返回行为）
- 关键返回：无（成功即表示 raw 已上传）

#### 6.1.3 upload-complete：登记文件元数据

```powershell
curl.exe -sS -X POST "$Base/files/upload-complete" -H "Authorization: Bearer $InternalToken" -H "Content-Type: $Json" -d "{\"fileId\":\"<FILE_ID>\",\"caseId\":\"$CaseId\",\"filename\":\"doc.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":123,\"sha256\":null,\"s3KeyRaw\":\"<S3_KEY_RAW>\"}"
```

- 期望：HTTP 200
- 关键返回：`fileId`

### 6.2 创建预览 token（external）

接口：`POST /preview/files/{fileId}/tokens`

```powershell
curl.exe -sS -X POST "$Base/preview/files/<FILE_ID>/tokens" -H "Authorization: Bearer $ExternalToken"
```

- 期望：HTTP 200
- 关键返回：`token` / `expiresAt`

> 说明：本系统不通过 query 参数指定 `variant`。
> 预览的 variant 由 viewer 的 `user_type` 自动决定：external 用户生成 external variant。

### 6.3 预览（external）

接口：`GET /preview?token=...`（返回 `application/pdf`）

```powershell
curl.exe -sS -L -X GET "$Base/preview?token=<PREVIEW_TOKEN>" -H "Authorization: Bearer $ExternalToken" -o preview-external.pdf
```

- 期望：HTTP 200
- 关键返回：响应为 PDF bytes（保存到 `preview-external.pdf`）

检查点：
- external 预览应为 image-based PDF（不可复制文本）。
- 页面应包含 EXTERNAL 强水印。
- external 禁止访问内部接口（如 `/files/**`、`/instructions/**` 等），会返回 403。

---

## 7) 报表：zone dashboard

接口：`GET /reports/zone-dashboard`

```powershell
curl.exe -sS -X GET "$Base/reports/zone-dashboard" -H "Authorization: Bearer $InternalToken"
```

- 期望：HTTP 200
- 关键返回：数组；每行包含 `groupId`，以及 grouped objects：`instruction` / `overdue` / `task` / `payment`
- `dayKey` 使用 Asia/Shanghai 时区，格式为 `yyyyMMdd`（例如 `20260111`）

---

## 8) 常见问题（简短）

1) PowerShell 的 curl 别名问题

- 现象：直接用 `curl` 实际执行的是 `Invoke-WebRequest`。
- 解决：强制使用 `curl.exe`（本手册所有命令均已如此写）。

2) 401 / 403 / 404 的常见含义

- 401：未携带 `Authorization: Bearer ...` 或 token 无效。
- 403：client/external 访问 internal API（InternalApiGuardFilter），或权限不足。
- 404：RLS/权限隔离下“不可见即不存在”（不泄露资源存在性）。

3) docker compose logs 排查

```powershell
docker compose ps
```

```powershell
docker compose logs -f api
```

```powershell
docker compose logs -f worker
```

---

## 真实接口清单表（从源码 Controller 扫描）

> 来源：`src/api/src/main/java/**/**Controller.java` 中的 `@RequestMapping/@GetMapping/@PostMapping`。
> 
> 说明：AUTH 为接口设计意图的最小分类：
> - `anonymous`：无需 JWT
> - `internal`：仅 internal/admin JWT（非 `/client/**` 且非 `/preview/**`）
> - `client`：仅 client JWT（`/client/**`）
> - `internal/client/external`：任意已认证用户可访问（`/preview/**`）

| METHOD | PATH | AUTH | 备注 |
|---|---|---|---|
| GET | /health | anonymous | 健康检查（返回纯文本 ok） |
| POST | /auth/sms/send | anonymous | 发送短信验证码（demo：验证码打印在 api logs） |
| POST | /auth/sms/verify | anonymous | 校验验证码并返回 JWT（`token`） |
| POST | /instructions | internal | 创建指令草稿（HTTP 201，`instructionId`） |
| POST | /instructions/{instructionId}/issue | internal | 下发指令；`Idempotency-Key` 必填；返回 `taskIds` |
| POST | /instruction-items/{instructionItemId}/status | internal | 更新指令项状态 |
| GET | /me/projects | internal | 我的项目列表 |
| GET | /me/tasks | internal | 我的任务列表（project-only task 的 `caseId` 可能为 null） |
| POST | /evidences | internal | 创建证据（HTTP 201，`evidenceId`） |
| POST | /tasks/{taskId}/payments | internal | 按任务创建回款；task.caseId 为空会 422（`TASK_HAS_NO_CASE`） |
| POST | /payments | internal | 创建回款；可选 `Idempotency-Key`；返回 `paymentId` |
| POST | /payments/{paymentId}/correct | internal | 更正回款；query: `reason`；可选 `Idempotency-Key`；返回 `newPaymentId` |
| POST | /files/upload-init | internal | 初始化上传（返回 `fileId/s3KeyRaw/presignedPutUrl/expiresAt`） |
| POST | /files/upload-complete | internal | 上传完成登记（返回 `fileId`） |
| POST | /preview/files/{fileId}/tokens | internal/client/external | 创建一次性预览 token（返回 `token/expiresAt`） |
| GET | /preview?token=... | internal/client/external | 获取预览 PDF bytes（external 预览为 image-based + EXTERNAL 水印） |
| GET | /client/projects | client | 客户项目列表 |
| GET | /client/projects/{projectId}/payments | client | 客户回款明细；可选 query: `caseId`；仅返回有效且 is_client_visible=true |
| POST | /client/payments/{paymentId}/disputes | client | 发起对账争议；可选 `Idempotency-Key`；返回 `disputeId/status`（HTTP 201） |
| GET | /client/complaints | client | 查询投诉/对账记录（字段白名单） |
| GET | /reports/zone-dashboard | internal | 区域看板报表；dayKey=Asia/Shanghai yyyyMMdd |
