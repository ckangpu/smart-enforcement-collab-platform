param(
  [string]$BaseUrl = "http://localhost:8080",
  [int]$HealthTimeoutSeconds = 120,
  [switch]$NoAutoCopyEnv,
  [switch]$DebugLogs,

  # Compose behavior:
  # - Default: `docker compose up -d` (recommended)
  # - Only when explicitly passing -BuildImages: `docker compose up -d --build`
  [switch]$BuildImages,

  # 推荐用于本地调试：不构建镜像，但会确保 compose 服务已启动。
  # - 若服务已在运行：不会中断现有服务
  # - 若服务未运行：会执行 `docker compose up -d`
  [switch]$SkipCompose
)

$ErrorActionPreference = "Stop"

function Invoke-NativeNoStop {
  param(
    [Parameter(Mandatory = $true)]
    [scriptblock]$Action
  )

  # Windows PowerShell 5.1 may treat native stderr output as non-terminating errors.
  # With $ErrorActionPreference = 'Stop', that would abort the script even when the
  # native command succeeds. We temporarily disable 'Stop' and rely on $LASTEXITCODE.
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $Action
  } finally {
    $ErrorActionPreference = $old
  }
}

function Fail([string]$message) {
  throw $message
}

function ExecStep([string]$title, [scriptblock]$action) {
  Write-Host "\n== $title =="
  & $action
}

function EnsureEnvFile() {
  $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
  $envPath = Join-Path $repoRoot ".env"
  $devExample = Join-Path $repoRoot ".env.dev.example"

  if (Test-Path $envPath) {
    return
  }

  if (-not (Test-Path $devExample)) {
    Fail "缺少 .env 和 .env.dev.example，请先创建 .env（仅限本地开发环境）。"
  }

  Write-Warning "未找到 .env。该仓库不会提交 .env。"
  Write-Host "请执行（仅限本地开发）：Copy-Item .env.dev.example .env"

  if ($NoAutoCopyEnv) {
    Fail "已指定 -NoAutoCopyEnv，脚本不会自动创建 .env。"
  }

  Copy-Item $devExample $envPath -Force
  Write-Host "已复制 .env.dev.example -> .env（仅限本地开发）。"
}

function WaitHealth() {
  $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $status = (Invoke-NativeNoStop { curl.exe -sS -o NUL -w "%{http_code}" "$BaseUrl/health" } | Out-String).Trim()
    if ($LASTEXITCODE -eq 0 -and $status -eq "200") {
      $body = (Invoke-NativeNoStop { curl.exe -sS "$BaseUrl/health" } | Out-String).Trim()
      if ($LASTEXITCODE -eq 0 -and $body -eq "ok") {
        Write-Host "健康检查通过：HTTP 200 ok"
        return
      }
    }

    Start-Sleep -Seconds 2
  }

  Fail "健康检查超时（${HealthTimeoutSeconds} 秒）：$BaseUrl/health"
}

function IsFlywayChecksumMismatch([string]$logs) {
  if ([string]::IsNullOrWhiteSpace($logs)) {
    return $false
  }

  return ($logs -match "Migration checksum mismatch" -or $logs -match "FlywayValidateException" -or $logs -match "Validate failed")
}

function ResetComposeState() {
  Write-Warning "检测到 Flyway 校验失败，将重置 docker compose 状态（down -v）。这会删除本地开发卷。"
  Invoke-NativeNoStop { docker compose down -v | Out-Host }
  if ($LASTEXITCODE -ne 0) { throw "docker compose down -v 执行失败（$LASTEXITCODE）" }
}

function ComposeUp([string[]]$composeArgs = @('-d', '--build')) {
  # IMPORTANT: stream output line-by-line so users don't think it's "hung",
  # and so callers can capture full logs with Tee-Object.
  $outLines = @()
  Invoke-NativeNoStop { docker compose up @composeArgs 2>&1 } | Tee-Object -Variable outLines | Out-Host
  if ($LASTEXITCODE -eq 0) {
    return
  }

  $out = ($outLines | Out-String)

  # Handle common local-dev flake: a leftover container with the same name blocks compose.
  # Example: The container name "/a91e703bcd33_secp-worker" is already in use by container "..."
  $rx = [regex]::new('The container name\s+"/?([^\"]+)"\s+is already in use', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
  $m = $rx.Match($out)
  if ($m.Success) {
    $conflictName = $m.Groups[1].Value
    Write-Warning "docker compose up 因容器名称冲突失败：$conflictName。将删除后重试一次。"
    Invoke-NativeNoStop { docker rm -f $conflictName | Out-Host }
    if ($LASTEXITCODE -ne 0) { throw "docker rm -f $conflictName 执行失败（$LASTEXITCODE）" }

    Invoke-NativeNoStop { docker compose up @composeArgs 2>&1 | Out-Host }
    if ($LASTEXITCODE -ne 0) { throw "清理冲突后 docker compose up 仍失败（$LASTEXITCODE）" }
    return
  }

  throw "docker compose up 执行失败（$LASTEXITCODE）"
}

function PrintComposePs() {
  try {
    Invoke-NativeNoStop { docker compose ps | Out-Host }
  } catch {
    # best-effort
  }
}

function GetApiLogs() {
  try {
    $logs = (Invoke-NativeNoStop { docker compose logs --no-color --tail 2000 api 2>$null | Out-String })
    if ($DebugLogs) {
      $outPath = Join-Path $PSScriptRoot ".smoke_logs_api.txt"
      Set-Content -Path $outPath -Value $logs -Encoding utf8
    }
    return $logs
  } catch {
    return ""
  }
}

function TryParseSmsCodeFromLogs([string]$logs, [string]$phone) {
  if ([string]::IsNullOrWhiteSpace($logs)) {
    return $null
  }

  # Must tolerate arbitrary prefixes/timestamps.
  # Examples:
  # - "[sms] phone=13900000002 code=123456"
  # - "<any prefix> phone=13900000002   code=123456"
  $escapedPhone = [regex]::Escape($phone)
  $rx = [regex]::new("phone\s*=\s*$escapedPhone\b.*?\bcode\s*=\s*(\d{4,8})\b", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)

  $lastCode = $null
  foreach ($line in ($logs -split "`r?`n")) {
    $m = $rx.Match($line)
    if ($m.Success) {
      $lastCode = $m.Groups[1].Value
    }
  }

  return $lastCode
}

function TryGetSmsCodeFromRedis([string]$phone) {
  try {
    $key = "sms:code:$phone"
    $raw = (Invoke-NativeNoStop { docker exec secp-redis redis-cli get $key 2>$null | Out-String })
    if ([string]::IsNullOrWhiteSpace($raw)) {
      return $null
    }
    $v = $raw.Trim()
    if ($v -eq "(nil)") {
      return $null
    }
    if ($v -match '^\d{4,8}$') {
      return $v
    }
    return $null
  } catch {
    return $null
  }
}

function ResetSmsLimitsInRedis([string]$phone) {
  try {
    # DEV ONLY: allow smoke to be re-run without being blocked by SMS cooldown/daily limit.
    $today = (Get-Date).ToString('yyyy-MM-dd')
    $dailyKey = "sms:daily:${phone}:$today"
    $cooldownKey = "sms:cooldown:${phone}"
    $codeKey = "sms:code:${phone}"
    Invoke-NativeNoStop { docker exec secp-redis redis-cli del $dailyKey $cooldownKey $codeKey 2>$null | Out-Host }
  } catch {
    # best-effort
  }
}

function GetSmsCode([string]$phone) {
  # Prefer Redis (source of truth for current code) to avoid picking up stale codes from logs.
  $code = TryGetSmsCodeFromRedis -phone $phone
  if ($null -ne $code -and $code -ne "") {
    Write-Host "已从 redis 读取 $phone 的短信验证码。"
    return $code
  }

  $logs = GetApiLogs
  $code = TryParseSmsCodeFromLogs -logs $logs -phone $phone
  if ($null -ne $code -and $code -ne "") {
    Write-Host "已从 api 日志解析到 $phone 的短信验证码。"
    return $code
  }

  Write-Warning "无法从日志中解析 $phone 的短信验证码。"
  Write-Host "提示：可执行 'docker compose logs --no-color --tail 2000 api'，查找：phone=$phone ... code=XXXXXX"
  return (Read-Host "请输入 $phone 的短信验证码")
}

function HttpPostJson([string]$url, [string]$jsonBody, [hashtable]$headers = @{}) {
  $tmp = Join-Path $env:TEMP ("secp_smoke_body_{0}.json" -f ([guid]::NewGuid().ToString("N")))
  try {
    Set-Content -Path $tmp -Value $jsonBody -Encoding utf8

    $curlParams = '-sS', '-X', 'POST', $url, '-H', 'Content-Type: application/json'
    foreach ($k in $headers.Keys) {
      $curlParams += '-H', "${k}: $($headers[$k])"
    }
    $curlParams += '--data-binary', "@$tmp"

    return (curl.exe @curlParams)
  } finally {
    if (Test-Path $tmp) {
      Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
  }
}

function HttpPostJsonWithStatus([string]$url, [string]$jsonBody, [hashtable]$headers = @{}) {
  $tmpIn = Join-Path $env:TEMP ("secp_smoke_in_{0}.json" -f ([guid]::NewGuid().ToString("N")))
  $tmpOut = Join-Path $env:TEMP ("secp_smoke_out_{0}.json" -f ([guid]::NewGuid().ToString("N")))
  try {
    Set-Content -Path $tmpIn -Value $jsonBody -Encoding utf8

    $curlParams = '-sS', '-X', 'POST', $url, '-H', 'Content-Type: application/json'
    foreach ($k in $headers.Keys) {
      $curlParams += '-H', "${k}: $($headers[$k])"
    }
    $curlParams += '--data-binary', "@$tmpIn", '-o', $tmpOut, '-w', '%{http_code}'

    $status = (curl.exe @curlParams).Trim()
    $body = ""
    if (Test-Path $tmpOut) {
      $body = (Get-Content -Raw -Encoding utf8 $tmpOut)
    }

    return [pscustomobject]@{ status = [int]$status; body = $body }
  } finally {
    if (Test-Path $tmpIn) { Remove-Item $tmpIn -Force -ErrorAction SilentlyContinue }
    if (Test-Path $tmpOut) { Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue }
  }
}

function HttpGetWithStatus([string]$url, [hashtable]$headers = @{}) {
  $tmpOut = Join-Path $env:TEMP ("secp_smoke_out_{0}.json" -f ([guid]::NewGuid().ToString("N")))
  try {
    $curlParams = '-sS', '-X', 'GET', $url
    foreach ($k in $headers.Keys) {
      $curlParams += '-H', "${k}: $($headers[$k])"
    }
    $curlParams += '-o', $tmpOut, '-w', '%{http_code}'

    $status = (curl.exe @curlParams).Trim()
    $body = ""
    if (Test-Path $tmpOut) {
      $body = (Get-Content -Raw -Encoding utf8 $tmpOut)
    }
    return [pscustomobject]@{ status = [int]$status; body = $body }
  } finally {
    if (Test-Path $tmpOut) { Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue }
  }
}

function DecodeBase64Url([string]$s) {
  $p = $s.Replace('-', '+').Replace('_', '/')
  switch ($p.Length % 4) {
    2 { $p += '==' }
    3 { $p += '=' }
  }
  $bytes = [Convert]::FromBase64String($p)
  return [Text.Encoding]::UTF8.GetString($bytes)
}

function GetJwtSubject([string]$jwt) {
  if ([string]::IsNullOrWhiteSpace($jwt)) { return $null }
  $parts = $jwt.Split('.')
  if ($parts.Length -lt 2) { return $null }
  $payloadJson = DecodeBase64Url $parts[1]
  try {
    $payload = $payloadJson | ConvertFrom-Json
    return $payload.sub
  } catch {
    return $null
  }
}

function HttpGet([string]$url, [hashtable]$headers = @{}) {
  $curlParams = '-sS', '-X', 'GET', $url
  foreach ($k in $headers.Keys) {
    $curlParams += '-H', "${k}: $($headers[$k])"
  }

  return (curl.exe @curlParams)
}

function GetJwtForPhone([string]$phone) {
  # Prefer existing code in Redis (useful for -SkipCompose and repeated runs).
  $code = TryGetSmsCodeFromRedis -phone $phone
  if ($null -ne $code -and $code -ne "") {
    Write-Host "将复用 redis 中已有的 $phone 短信验证码。"
  } else {
    $sendBody = ('{{"phone":"{0}"}}' -f $phone)
    $sendResp = HttpPostJsonWithStatus -url "$BaseUrl/auth/sms/send" -jsonBody $sendBody
    if ($sendResp.status -ne 200) {
      Write-Warning "POST /auth/sms/send 返回 $($sendResp.status)（phone=$phone）。响应：$($sendResp.body)"

      if ($sendResp.status -eq 429 -and ($sendResp.body -match 'SMS_DAILY_LIMIT' -or $sendResp.body -match 'SMS_COOLDOWN')) {
        Write-Warning "仅限本地开发：将重置 $phone 的短信限制 key 并重试一次。"
        ResetSmsLimitsInRedis -phone $phone
        $sendResp = HttpPostJsonWithStatus -url "$BaseUrl/auth/sms/send" -jsonBody $sendBody
        if ($sendResp.status -ne 200) {
          Write-Warning "重试 POST /auth/sms/send 仍返回 $($sendResp.status)（phone=$phone）。响应：$($sendResp.body)"
        }
      }
    }

    # Give Redis/logs a moment to flush.
    Start-Sleep -Milliseconds 300
    $code = TryGetSmsCodeFromRedis -phone $phone
  }

  if ([string]::IsNullOrWhiteSpace($code)) {
    $code = GetSmsCode -phone $phone
  }
  if ([string]::IsNullOrWhiteSpace($code)) {
    Fail "短信验证码为空（phone=$phone）"
  }

  $verifyBody = ('{{"phone":"{0}","code":"{1}"}}' -f $phone, $code)
  $verifyResp = HttpPostJsonWithStatus -url "$BaseUrl/auth/sms/verify" -jsonBody $verifyBody
  if ($verifyResp.status -ne 200) {
    Fail "POST /auth/sms/verify 期望 200，实际 $($verifyResp.status)（phone=$phone）。响应：$($verifyResp.body)"
  }

  try {
    $json = $verifyResp.body | ConvertFrom-Json
  } catch {
    Fail "无法将 /auth/sms/verify 响应解析为 JSON（phone=$phone）。原始响应：$($verifyResp.body)"
  }

  if ($null -eq $json.token -or [string]::IsNullOrWhiteSpace($json.token)) {
    Fail "/auth/sms/verify 响应缺少 token（phone=$phone）。原始响应：$($verifyResp.body)"
  }

  return $json.token
}

function AssertZoneDashboard([string]$internalToken) {
  $r = HttpGetWithStatus -url "$BaseUrl/reports/zone-dashboard" -headers @{ Authorization = "Bearer $internalToken" }
  if ($r.status -ne 200) {
    Fail "GET /reports/zone-dashboard 期望 200，实际 $($r.status)。响应：$($r.body)"
  }

  try {
    $json = $r.body | ConvertFrom-Json
  } catch {
    Fail "无法将 /reports/zone-dashboard 解析为 JSON。原始响应：$($r.body)"
  }

  if ($null -eq $json) {
    Fail "/reports/zone-dashboard 响应为空"
  }

  if (-not ($json -is [System.Array]) -or $json.Count -lt 1) {
    Fail "/reports/zone-dashboard 期望返回非空数组"
  }

  $row = $json | Select-Object -First 1
  if ($null -eq $row.groupId -or [string]::IsNullOrWhiteSpace([string]$row.groupName)) {
    Fail "zone dashboard 行缺少 groupId/groupName。原始响应：$($r.body)"
  }

  foreach ($key in @('instruction','overdue','task','payment')) {
    if (-not ($row.PSObject.Properties.Name -contains $key)) {
      Fail "zone dashboard 缺少字段 '$key'。"
    }
  }
}

try {
  $composeUpArgs = @('-d')
  if ($BuildImages) {
    $composeUpArgs = @('-d', '--build')
  }

  ExecStep "预检：.env" { EnsureEnvFile }

  if ($SkipCompose) {
    ExecStep "Compose：确保服务已启动（不构建镜像）" {
      ComposeUp @('-d')
      PrintComposePs
    }
  } else {
    ExecStep "Compose：启动服务（buildImages=$BuildImages）" {
      ComposeUp $composeUpArgs
      PrintComposePs
    }
  }

  ExecStep "等待 /health（最长 ${HealthTimeoutSeconds} 秒）" {
    $retried = $false
    while ($true) {
      try {
        WaitHealth
        return
      } catch {
        if ($retried) { throw }

        $logs = GetApiLogs
        if (IsFlywayChecksumMismatch -logs $logs) {
          Write-Warning "API 未就绪，检测到 Flyway 校验/校验和不一致。将重置卷并重试一次。"
          ResetComposeState
          ComposeUp $composeUpArgs
          PrintComposePs
          $retried = $true
          continue
        }

        throw
      }
    }
  }

  ExecStep "导入 dev seed" {
    & (Join-Path $PSScriptRoot "seed-dev.ps1")
  }

  ExecStep "登录：获取 internal/client/external JWT" {
    $script:InternalToken = GetJwtForPhone -phone "13900000002"
    $script:ClientToken   = GetJwtForPhone -phone "13900000001"
    $script:ExternalToken = GetJwtForPhone -phone "13900000003"

    Write-Host "已获取 3 个 JWT。"
  }

  ExecStep "Admin 初始化：创建项目/案件并添加成员" {
    $groupId = "11111111-1111-1111-1111-111111111111"
    $internalUserId = GetJwtSubject -jwt $script:InternalToken
    if ([string]::IsNullOrWhiteSpace($internalUserId)) {
      Fail "无法从 JWT 的 sub 字段解析 internal userId"
    }

    $createProjectBody = (@{
      groupId = $groupId
      name = "SMOKE Project $(Get-Date -Format 'yyyyMMdd-HHmmss')"
      bizTags = @("smoke")
      acceptedAt = (Get-Date).ToString('yyyy-MM-dd')
      codeSource = "AUTO"
    } | ConvertTo-Json -Depth 5)

    $pr = HttpPostJsonWithStatus -url "$BaseUrl/admin/projects" -jsonBody $createProjectBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($pr.status -ne 201) {
      Fail "POST /admin/projects 期望 201，实际 $($pr.status)。响应：$($pr.body)"
    }
    $pj = $pr.body | ConvertFrom-Json
    $script:ProjectId = $pj.projectId

    $createCaseBody = (@{
      projectId = $script:ProjectId
      title = "SMOKE Case $(Get-Date -Format 'yyyyMMdd-HHmmss')"
      acceptedAt = (Get-Date).ToString('yyyy-MM-dd')
      codeSource = "AUTO"
    } | ConvertTo-Json -Depth 5)

    $cr = HttpPostJsonWithStatus -url "$BaseUrl/admin/cases" -jsonBody $createCaseBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($cr.status -ne 201) {
      Fail "POST /admin/cases 期望 201，实际 $($cr.status)。响应：$($cr.body)"
    }
    $cj = $cr.body | ConvertFrom-Json
    $script:CaseId = $cj.caseId

    $addProjectMemberBody = (@{ userId = $internalUserId; role = "member" } | ConvertTo-Json)
    $mr1 = HttpPostJsonWithStatus -url "$BaseUrl/admin/projects/$($script:ProjectId)/members" -jsonBody $addProjectMemberBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($mr1.status -ne 200) {
      Fail "POST /admin/projects/{id}/members 期望 200，实际 $($mr1.status)。响应：$($mr1.body)"
    }

    $addCaseMemberBody = (@{ userId = $internalUserId; role = "assignee" } | ConvertTo-Json)
    $mr2 = HttpPostJsonWithStatus -url "$BaseUrl/admin/cases/$($script:CaseId)/members" -jsonBody $addCaseMemberBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($mr2.status -ne 200) {
      Fail "POST /admin/cases/{id}/members 期望 200，实际 $($mr2.status)。响应：$($mr2.body)"
    }

    Write-Host "初始化完成。projectId=$($script:ProjectId) caseId=$($script:CaseId) internalUserId=$internalUserId"
  }

  ExecStep "指令：创建并下发（Idempotency-Key）" {
    $createInstrBody = (@{
      refType = "case"
      refId = $script:CaseId
      title = "Instr (smoke)"
      items = @(@{ title = "item-1" })
    } | ConvertTo-Json -Depth 6)

    $ir = HttpPostJsonWithStatus -url "$BaseUrl/instructions" -jsonBody $createInstrBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($ir.status -ne 201) {
      Fail "POST /instructions 期望 201，实际 $($ir.status)。响应：$($ir.body)"
    }
    $ij = $ir.body | ConvertFrom-Json
    $script:InstructionId = $ij.instructionId

    $idem = [guid]::NewGuid().ToString()
    $issueBody = "{}"
    $iss = HttpPostJsonWithStatus -url "$BaseUrl/instructions/$($script:InstructionId)/issue" -jsonBody $issueBody -headers @{ Authorization = "Bearer $script:InternalToken"; "Idempotency-Key" = $idem }
    if ($iss.status -ne 200) {
      Fail "POST /instructions/{id}/issue 期望 200，实际 $($iss.status)。响应：$($iss.body)"
    }
    Write-Host "指令已下发 instructionId=$($script:InstructionId)"
  }

  ExecStep "校验：/me/tasks 可看到案件任务" {
    $tr = HttpGetWithStatus -url "$BaseUrl/me/tasks?caseId=$($script:CaseId)" -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($tr.status -ne 200) {
      Fail "GET /me/tasks 期望 200，实际 $($tr.status)。响应：$($tr.body)"
    }
    $tasks = $tr.body | ConvertFrom-Json
    if ($null -eq $tasks -or ($tasks | Measure-Object).Count -lt 1) {
      Fail "期望 caseId=$($script:CaseId) 至少 1 条任务。原始响应：$($tr.body)"
    }
  }

  ExecStep "客户端：GET /client/projects（不泄露内部字段）" {
    $cp = HttpGetWithStatus -url "$BaseUrl/client/projects" -headers @{ Authorization = "Bearer $script:ClientToken" }
    if ($cp.status -ne 200) {
      Fail "GET /client/projects 期望 200，实际 $($cp.status)。响应：$($cp.body)"
    }
  }

  ExecStep "校验：/reports/zone-dashboard" {
    AssertZoneDashboard -internalToken $script:InternalToken
    Write-Host "zone dashboard 校验通过。"
  }

  ExecStep "项目：GET /projects/{id}/detail" {
    $dr = HttpGetWithStatus -url "$BaseUrl/projects/$($script:ProjectId)/detail" -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($dr.status -ne 200) {
      Fail "GET /projects/{id}/detail 期望 200，实际 $($dr.status)。响应：$($dr.body)"
    }
  }

  ExecStep "项目：GET /projects/{id}/a4.pdf（检查 PDF 头）" {
    $tmpDir = Join-Path $PSScriptRoot ".smoke_tmp"
    New-Item -ItemType Directory -Force $tmpDir | Out-Null
    $hdrPath = Join-Path $tmpDir "project_a4_headers.txt"
    $pdfPath = Join-Path $tmpDir "project_a4.pdf"

    $status = & curl.exe -sS -D $hdrPath -o $pdfPath -H "Authorization: Bearer $script:InternalToken" -w "%{http_code}" "$BaseUrl/projects/$($script:ProjectId)/a4.pdf"
    if ([int]$status -ne 200) {
      $hdr = ""
      if (Test-Path $hdrPath) { $hdr = Get-Content $hdrPath -Raw }
      Fail "GET /projects/{id}/a4.pdf 期望 200，实际 $status。响应头：$hdr"
    }

    $hdr = Get-Content $hdrPath -Raw
    $lines = $hdr -split "`r?`n"
    $ctLine = $lines | Where-Object { $_ -like 'Content-Type:*' } | Select-Object -First 1
    if ($null -eq $ctLine -or $ctLine.ToLowerInvariant() -notlike '*application/pdf*') {
      Fail "期望 Content-Type 为 application/pdf。响应头：$hdr"
    }

    if (-not (Test-Path $pdfPath)) {
      Fail "未生成 project_a4.pdf 文件。"
    }
    $len = (Get-Item $pdfPath).Length
    if ($len -le 3000) {
      Fail "PDF 内容长度过小（$len 字节），疑似空 PDF。"
    }
  }

  ExecStep "案件：GET /cases/{id}/detail" {
    $dr2 = HttpGetWithStatus -url "$BaseUrl/cases/$($script:CaseId)/detail" -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($dr2.status -ne 200) {
      Fail "GET /cases/{id}/detail 期望 200，实际 $($dr2.status)。响应：$($dr2.body)"
    }
  }

  ExecStep "案件：GET /cases/{id}/a4.pdf（检查 PDF 头）" {
    $tmpDir = Join-Path $PSScriptRoot ".smoke_tmp"
    New-Item -ItemType Directory -Force $tmpDir | Out-Null
    $hdrPath = Join-Path $tmpDir "case_a4_headers.txt"
    $pdfPath = Join-Path $tmpDir "case_a4.pdf"

    $status = & curl.exe -sS -D $hdrPath -o $pdfPath -H "Authorization: Bearer $script:InternalToken" -w "%{http_code}" "$BaseUrl/cases/$($script:CaseId)/a4.pdf"
    if ([int]$status -ne 200) {
      $hdr = ""
      if (Test-Path $hdrPath) { $hdr = Get-Content $hdrPath -Raw }
      Fail "GET /cases/{id}/a4.pdf 期望 200，实际 $status。响应头：$hdr"
    }

    $hdr = Get-Content $hdrPath -Raw
    $lines = $hdr -split "`r?`n"
    $ctLine = $lines | Where-Object { $_ -like 'Content-Type:*' } | Select-Object -First 1
    if ($null -eq $ctLine -or $ctLine.ToLowerInvariant() -notlike '*application/pdf*') {
      Fail "期望 Content-Type 为 application/pdf。响应头：$hdr"
    }

    if (-not (Test-Path $pdfPath)) {
      Fail "未生成 case_a4.pdf 文件。"
    }
    $len = (Get-Item $pdfPath).Length
    if ($len -le 3000) {
      Fail "PDF 内容长度过小（$len 字节），疑似空 PDF。"
    }
  }

  Write-Host "\nSMOKE 通过"
  exit 0
} catch {
  Write-Error $_
  exit 1
}
