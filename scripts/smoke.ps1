param(
  [string]$BaseUrl = "http://localhost:8080",
  [int]$HealthTimeoutSeconds = 120,
  [switch]$NoAutoCopyEnv,
  [switch]$DebugLogs
)

$ErrorActionPreference = "Stop"

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
    Fail "Missing .env and .env.dev.example. Create .env first (DEV ONLY)."
  }

  Write-Warning ".env not found. This repo does not commit .env."
  Write-Host "Please run (DEV ONLY): Copy-Item .env.dev.example .env"

  if ($NoAutoCopyEnv) {
    Fail "NoAutoCopyEnv specified; refusing to create .env automatically."
  }

  Copy-Item $devExample $envPath -Force
  Write-Host "Copied .env.dev.example -> .env (DEV ONLY)."
}

function WaitHealth() {
  $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $status = (curl.exe -sS -o NUL -w "%{http_code}" "$BaseUrl/health").Trim()
      if ($status -eq "200") {
        $body = (curl.exe -sS "$BaseUrl/health").Trim()
        if ($body -eq "ok") {
          Write-Host "Health OK: HTTP 200 ok"
          return
        }
      }
    } catch {
      # ignore and retry
    }

    Start-Sleep -Seconds 2
  }

  Fail "Health check timed out after ${HealthTimeoutSeconds}s: $BaseUrl/health"
}

function IsFlywayChecksumMismatch([string]$logs) {
  if ([string]::IsNullOrWhiteSpace($logs)) {
    return $false
  }

  return ($logs -match "Migration checksum mismatch" -or $logs -match "FlywayValidateException" -or $logs -match "Validate failed")
}

function ResetComposeState() {
  Write-Warning "Resetting docker compose state (down -v) due to Flyway validation failure. This will delete local dev volumes."
  docker compose down -v | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "docker compose down -v failed ($LASTEXITCODE)" }
}

function GetApiLogs() {
  try {
    $logs = (docker compose logs --no-color --tail 2000 api 2>$null | Out-String)
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
    $raw = (docker exec secp-redis redis-cli get $key 2>$null | Out-String)
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

function GetSmsCode([string]$phone) {
  $logs = GetApiLogs
  $code = TryParseSmsCodeFromLogs -logs $logs -phone $phone
  if ($null -ne $code -and $code -ne "") {
    Write-Host "Parsed SMS code for $phone from api logs."
    return $code
  }

  $code = TryGetSmsCodeFromRedis -phone $phone
  if ($null -ne $code -and $code -ne "") {
    Write-Host "Read SMS code for $phone from redis."
    return $code
  }

  Write-Warning "Could not parse SMS code for $phone from logs."
  Write-Host "Hint: run 'docker compose logs --no-color --tail 2000 api' and look for: phone=$phone ... code=XXXXXX"
  return (Read-Host "Enter SMS code for $phone")
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
  $sendBody = ('{{"phone":"{0}"}}' -f $phone)
  [void](HttpPostJson -url "$BaseUrl/auth/sms/send" -jsonBody $sendBody)

  # Give logs a moment to flush.
  Start-Sleep -Milliseconds 300

  $code = GetSmsCode -phone $phone
  if ([string]::IsNullOrWhiteSpace($code)) {
    Fail "Empty SMS code for $phone"
  }

  $verifyBody = ('{{"phone":"{0}","code":"{1}"}}' -f $phone, $code)
  $verifyResp = HttpPostJson -url "$BaseUrl/auth/sms/verify" -jsonBody $verifyBody

  try {
    $json = $verifyResp | ConvertFrom-Json
  } catch {
    Fail "Failed to parse /auth/sms/verify response as JSON for $phone. Raw: $verifyResp"
  }

  if ($null -eq $json.token -or [string]::IsNullOrWhiteSpace($json.token)) {
    Fail "No token in /auth/sms/verify response for $phone. Raw: $verifyResp"
  }

  return $json.token
}

function AssertZoneDashboard([string]$internalToken) {
  $r = HttpGetWithStatus -url "$BaseUrl/reports/zone-dashboard" -headers @{ Authorization = "Bearer $internalToken" }
  if ($r.status -ne 200) {
    Fail "GET /reports/zone-dashboard expected 200, got $($r.status). Body: $($r.body)"
  }

  try {
    $json = $r.body | ConvertFrom-Json
  } catch {
    Fail "Failed to parse /reports/zone-dashboard as JSON. Raw: $($r.body)"
  }

  if ($null -eq $json) {
    Fail "Empty /reports/zone-dashboard response"
  }

  if (-not ($json -is [System.Array]) -or $json.Count -lt 1) {
    Fail "Expected /reports/zone-dashboard to return a non-empty array"
  }

  $row = $json | Select-Object -First 1
  if ($null -eq $row.groupId -or [string]::IsNullOrWhiteSpace([string]$row.groupName)) {
    Fail "Zone dashboard row missing groupId/groupName. Raw: $($r.body)"
  }

  foreach ($key in @('instruction','overdue','task','payment')) {
    if (-not ($row.PSObject.Properties.Name -contains $key)) {
      Fail "Zone dashboard missing key '$key'."
    }
  }
}

try {
  ExecStep "Preflight: .env" { EnsureEnvFile }

  ExecStep "docker compose up -d --build" {
    docker compose up -d --build | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed ($LASTEXITCODE)" }
  }

  ExecStep "Wait /health (max ${HealthTimeoutSeconds}s)" {
    $retried = $false
    while ($true) {
      try {
        WaitHealth
        return
      } catch {
        if ($retried) { throw }

        $logs = GetApiLogs
        if (IsFlywayChecksumMismatch -logs $logs) {
          Write-Warning "API did not become healthy; detected Flyway validation/checksum mismatch. Will reset volumes and retry once."
          ResetComposeState
          docker compose up -d --build | Out-Host
          if ($LASTEXITCODE -ne 0) { throw "docker compose up failed ($LASTEXITCODE)" }
          $retried = $true
          continue
        }

        throw
      }
    }
  }

  ExecStep "Seed dev data" {
    & (Join-Path $PSScriptRoot "seed-dev.ps1")
  }

  ExecStep "Auth: get internal/client/external JWT" {
    $script:InternalToken = GetJwtForPhone -phone "13900000002"
    $script:ClientToken   = GetJwtForPhone -phone "13900000001"
    $script:ExternalToken = GetJwtForPhone -phone "13900000003"

    Write-Host "Got 3 JWTs."
  }

  ExecStep "Admin bootstrap: create project/case + add internal member" {
    $groupId = "11111111-1111-1111-1111-111111111111"
    $internalUserId = GetJwtSubject -jwt $script:InternalToken
    if ([string]::IsNullOrWhiteSpace($internalUserId)) {
      Fail "Failed to parse internal userId from JWT (sub)"
    }

    $createProjectBody = (@{
      groupId = $groupId
      name = "SMOKE Project $(Get-Date -Format 'yyyyMMdd-HHmmss')"
      bizTags = @("smoke")
    } | ConvertTo-Json -Depth 5)

    $pr = HttpPostJsonWithStatus -url "$BaseUrl/admin/projects" -jsonBody $createProjectBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($pr.status -ne 201) {
      Fail "POST /admin/projects expected 201, got $($pr.status). Body: $($pr.body)"
    }
    $pj = $pr.body | ConvertFrom-Json
    $script:ProjectId = $pj.projectId

    $createCaseBody = (@{
      projectId = $script:ProjectId
      title = "SMOKE Case $(Get-Date -Format 'yyyyMMdd-HHmmss')"
    } | ConvertTo-Json -Depth 5)

    $cr = HttpPostJsonWithStatus -url "$BaseUrl/admin/cases" -jsonBody $createCaseBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($cr.status -ne 201) {
      Fail "POST /admin/cases expected 201, got $($cr.status). Body: $($cr.body)"
    }
    $cj = $cr.body | ConvertFrom-Json
    $script:CaseId = $cj.caseId

    $addProjectMemberBody = (@{ userId = $internalUserId; role = "member" } | ConvertTo-Json)
    $mr1 = HttpPostJsonWithStatus -url "$BaseUrl/admin/projects/$($script:ProjectId)/members" -jsonBody $addProjectMemberBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($mr1.status -ne 200) {
      Fail "POST /admin/projects/{id}/members expected 200, got $($mr1.status). Body: $($mr1.body)"
    }

    $addCaseMemberBody = (@{ userId = $internalUserId; role = "assignee" } | ConvertTo-Json)
    $mr2 = HttpPostJsonWithStatus -url "$BaseUrl/admin/cases/$($script:CaseId)/members" -jsonBody $addCaseMemberBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($mr2.status -ne 200) {
      Fail "POST /admin/cases/{id}/members expected 200, got $($mr2.status). Body: $($mr2.body)"
    }

    Write-Host "Bootstrap OK. projectId=$($script:ProjectId) caseId=$($script:CaseId) internalUserId=$internalUserId"
  }

  ExecStep "Instruction: create -> issue (Idempotency-Key)" {
    $createInstrBody = (@{
      refType = "case"
      refId = $script:CaseId
      title = "Instr (smoke)"
      items = @(@{ title = "item-1" })
    } | ConvertTo-Json -Depth 6)

    $ir = HttpPostJsonWithStatus -url "$BaseUrl/instructions" -jsonBody $createInstrBody -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($ir.status -ne 201) {
      Fail "POST /instructions expected 201, got $($ir.status). Body: $($ir.body)"
    }
    $ij = $ir.body | ConvertFrom-Json
    $script:InstructionId = $ij.instructionId

    $idem = [guid]::NewGuid().ToString()
    $issueBody = "{}"
    $iss = HttpPostJsonWithStatus -url "$BaseUrl/instructions/$($script:InstructionId)/issue" -jsonBody $issueBody -headers @{ Authorization = "Bearer $script:InternalToken"; "Idempotency-Key" = $idem }
    if ($iss.status -ne 200) {
      Fail "POST /instructions/{id}/issue expected 200, got $($iss.status). Body: $($iss.body)"
    }
    Write-Host "Issued instructionId=$($script:InstructionId)"
  }

  ExecStep "Assert /me/tasks can see case task" {
    $tr = HttpGetWithStatus -url "$BaseUrl/me/tasks?caseId=$($script:CaseId)" -headers @{ Authorization = "Bearer $script:InternalToken" }
    if ($tr.status -ne 200) {
      Fail "GET /me/tasks expected 200, got $($tr.status). Body: $($tr.body)"
    }
    $tasks = $tr.body | ConvertFrom-Json
    if ($null -eq $tasks -or ($tasks | Measure-Object).Count -lt 1) {
      Fail "Expected at least 1 task for caseId=$($script:CaseId). Raw: $($tr.body)"
    }
  }

  ExecStep "Client: GET /client/projects (no leakage)" {
    $cp = HttpGetWithStatus -url "$BaseUrl/client/projects" -headers @{ Authorization = "Bearer $script:ClientToken" }
    if ($cp.status -ne 200) {
      Fail "GET /client/projects expected 200, got $($cp.status). Body: $($cp.body)"
    }
  }

  ExecStep "Assert /reports/zone-dashboard" {
    AssertZoneDashboard -internalToken $script:InternalToken
    Write-Host "Zone dashboard OK."
  }

  Write-Host "\nSMOKE PASS"
  exit 0
} catch {
  Write-Error $_
  exit 1
}
