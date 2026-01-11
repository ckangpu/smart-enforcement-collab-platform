param(
  [string]$BaseUrl = "http://localhost:8080",
  [int]$HealthTimeoutSeconds = 60,
  [switch]$NoAutoCopyEnv
)

$ErrorActionPreference = "Stop"

function Fail([string]$message) {
  Write-Error $message
  exit 1
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

function GetApiLogs() {
  try {
    return (docker compose logs --no-color --tail=2000 api 2>$null | Out-String)
  } catch {
    return ""
  }
}

function TryParseSmsCodeFromLogs([string]$logs, [string]$phone) {
  if ([string]::IsNullOrWhiteSpace($logs)) {
    return $null
  }

  # Expected demo format: [sms] phone=13900000002 code=123456
  $pattern = "\[sms\]\s*phone=$phone\s*code=(\d{4,8})"
  $rx = [regex]$pattern
  $m = $rx.Match($logs)
  $last = $null
  while ($m.Success) {
    $last = $m
    $m = $m.NextMatch()
  }
  if ($null -ne $last) {
    return $last.Groups[1].Value
  }

  return $null
}

function GetSmsCode([string]$phone) {
  $logs = GetApiLogs
  $code = TryParseSmsCodeFromLogs -logs $logs -phone $phone
  if ($null -ne $code -and $code -ne "") {
    Write-Host "Parsed SMS code for $phone from api logs."
    return $code
  }

  Write-Warning "Could not parse SMS code for $phone from logs."
  Write-Host "Hint: run 'docker compose logs -f api' and look for: [sms] phone=$phone code=XXXXXX"
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
  $resp = HttpGet -url "$BaseUrl/reports/zone-dashboard" -headers @{ Authorization = "Bearer $internalToken" }

  try {
    $json = $resp | ConvertFrom-Json
  } catch {
    Fail "Failed to parse /reports/zone-dashboard as JSON. Raw: $resp"
  }

  if ($null -eq $json) {
    Fail "Empty /reports/zone-dashboard response"
  }

  $row = $json
  if ($json -is [System.Array]) {
    if ($json.Count -lt 1) {
      Fail "Expected /reports/zone-dashboard to return a non-empty array"
    }
    $row = $json | Select-Object -First 1
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

  ExecStep "Wait /health (max ${HealthTimeoutSeconds}s)" { WaitHealth }

  ExecStep "Seed dev data" {
    & (Join-Path $PSScriptRoot "seed-dev.ps1")
  }

  ExecStep "Auth: get internal/client/external JWT" {
    $script:InternalToken = GetJwtForPhone -phone "13900000002"
    $script:ClientToken   = GetJwtForPhone -phone "13900000001"
    $script:ExternalToken = GetJwtForPhone -phone "13900000003"

    Write-Host "Got 3 JWTs."
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
