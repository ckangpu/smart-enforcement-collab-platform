param(
  [string]$Phone = "13777777392",
  [string]$UserId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9",
  [string]$Password = "800810"
)

$ErrorActionPreference = "Stop"

# Ensure piping to external commands uses UTF-8.
$OutputEncoding = [System.Text.UTF8Encoding]::new()

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Ensure-HealthOk {
  param([int]$TimeoutSeconds = 60)

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $resp = & curl.exe -sS http://localhost:8080/health
      if ($LASTEXITCODE -eq 0 -and $resp -eq "ok") {
        return
      }
    } catch {
      # ignore
    }
    Start-Sleep -Seconds 1
  }

  throw "API /health not ok within ${TimeoutSeconds}s. Is docker compose up -d running?"
}

function Invoke-ApiJsonPost {
  param(
    [string]$Url,
    [hashtable]$Body,
    [hashtable]$Headers = @{}
  )

  $json = $Body | ConvertTo-Json -Compress

  $tmpBody = New-TemporaryFile
  $tmpHdr = New-TemporaryFile
  $tmpJson = New-TemporaryFile
  try {
    # Write JSON without BOM; avoid PowerShell/curl argument-escaping issues on Windows.
    [System.IO.File]::WriteAllText($tmpJson, $json, [System.Text.UTF8Encoding]::new($false))

    $hdrArgs = @()
    foreach ($k in $Headers.Keys) {
      $hdrArgs += "-H"
      $hdrArgs += "${k}: $($Headers[$k])"
    }

    & curl.exe -sS -D $tmpHdr -o $tmpBody -H "Content-Type: application/json" @hdrArgs -X POST --data-binary ("@" + $tmpJson) $Url | Out-Null

    $httpLines = @(Get-Content -Path $tmpHdr | Where-Object { $_ -match '^HTTP/' })
    if ($null -eq $httpLines -or $httpLines.Count -lt 1) {
      throw "Failed to parse HTTP status lines. Raw headers: $(Get-Content -Path $tmpHdr -Raw)"
    }

    $statusLine = $httpLines[-1].Trim()
    $parts = $statusLine -split '\s+'
    if ($parts.Count -lt 2) {
      $codes = ($statusLine.ToCharArray() | ForEach-Object { [int][char]$_ }) -join ','
      throw "Failed to parse HTTP status line: '$statusLine' charCodes=[$codes]"
    }

    try {
      $status = [int]$parts[1]
    } catch {
      $codes = ($statusLine.ToCharArray() | ForEach-Object { [int][char]$_ }) -join ','
      throw "Failed to parse HTTP status line: '$statusLine' charCodes=[$codes]"
    }
    $bodyText = Get-Content -Path $tmpBody -Raw

    return [pscustomobject]@{ Status = $status; Body = $bodyText }
  }
  finally {
    Remove-Item $tmpBody, $tmpHdr, $tmpJson -ErrorAction SilentlyContinue
  }
}

function Reset-SmsLimitsDevOnly {
  param([string]$Phone)

  $today = (Get-Date).ToString("yyyy-MM-dd")
  $keys = @(
    "sms:cooldown:${Phone}",
    "sms:daily:${Phone}:${today}",
    "sms:code:${Phone}"
  )

  Push-Location $RepoRoot
  try {
    foreach ($k in $keys) {
      & docker compose exec -T redis redis-cli DEL $k | Out-Null
    }
  }
  finally {
    Pop-Location
  }
}

function Get-LatestSmsCodeFromApiLogs {
  param([string]$Phone)

  Push-Location $RepoRoot
  try {
    $logs = & docker compose logs --no-color --tail 2000 api
  }
  finally {
    Pop-Location
  }

  $lines = $logs
  if (-not ($lines -is [array])) {
    $lines = @($lines)
  }

  $phoneEsc = [regex]::Escape($Phone)
  $pattern = "\[sms\]\s+phone=${phoneEsc}\s+code=(\d{4,8})"

  $codes = @()
  foreach ($line in $lines) {
    if ($line -match $pattern) {
      $codes += $Matches[1]
    }
  }

  if ($codes.Count -le 0) {
    throw "Failed to find SMS code for phone=$Phone in api logs. (Looked for pattern: $pattern)"
  }

  return $codes[$codes.Count - 1]
}

Write-Host "DEV ONLY: set password via SMS token + admin endpoint"
Write-Host "- Phone:    $Phone"
Write-Host "- UserId:   $UserId"
Write-Host "- Password: (hidden)"

Ensure-HealthOk -TimeoutSeconds 60
Write-Host "Health check ok."

# 1) /auth/sms/send
$send1 = Invoke-ApiJsonPost -Url "http://localhost:8080/auth/sms/send" -Body @{ phone = $Phone }
if ($send1.Status -eq 429) {
  Write-Host "SMS send got 429 (cooldown/daily limit). DEV-only resetting redis sms keys and retrying once..."
  Reset-SmsLimitsDevOnly -Phone $Phone
  $send2 = Invoke-ApiJsonPost -Url "http://localhost:8080/auth/sms/send" -Body @{ phone = $Phone }
  if ($send2.Status -ne 200) {
    throw "SMS send retry failed: HTTP $($send2.Status) body=$($send2.Body)"
  }
}
elseif ($send1.Status -ne 200) {
  throw "SMS send failed: HTTP $($send1.Status) body=$($send1.Body)"
}

# 2) parse latest code from api logs
Start-Sleep -Seconds 1
$code = Get-LatestSmsCodeFromApiLogs -Phone $Phone
Write-Host "Got SMS code from logs: $code"

# 3) /auth/sms/verify => token
$verify = Invoke-ApiJsonPost -Url "http://localhost:8080/auth/sms/verify" -Body @{ phone = $Phone; code = $code }
if ($verify.Status -ne 200) {
  throw "SMS verify failed: HTTP $($verify.Status) body=$($verify.Body)"
}

$verifyJson = $verify.Body | ConvertFrom-Json
$token = $verifyJson.token
if ([string]::IsNullOrWhiteSpace($token)) {
  throw "SMS verify did not return token. body=$($verify.Body)"
}

# 4) /admin/users/{UserId}/password
$setPwd = Invoke-ApiJsonPost -Url "http://localhost:8080/admin/users/$UserId/password" -Body @{ password = $Password } -Headers @{ Authorization = "Bearer $token" }
if ($setPwd.Status -ne 200) {
  throw "Set password failed: HTTP $($setPwd.Status) body=$($setPwd.Body)"
}

Write-Host "OK. Password updated for userId=$UserId"
Write-Host "You can now login via UI password tab: http://localhost:8080/ui/login.html"
Write-Host "- username: kangpu  password: $Password"
Write-Host "- username: $Phone  password: $Password"
