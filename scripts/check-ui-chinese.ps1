param(
  [switch]$VerboseOutput
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# Disallowed common English UI words/phrases (case-insensitive).
# Keep this list small and high-signal to reduce false positives.
$disallowed = @(
  'api logs',
  'project-only',
  'login', 'logout',
  'username', 'password', 'token',
  'submit', 'cancel',
  'loading',
  'no data',
  'download', 'export', 'print',
  'error',
  'unauthorized', 'forbidden', 'not found',
  'bad request',
  'internal server error'
)

$pattern = '(?i)(' + (($disallowed | ForEach-Object { [Regex]::Escape($_) }) -join '|') + ')'

# Allowlist for common technical strings that may appear in UI code.
$allowedExact = @(
  'Authorization',
  'Content-Type',
  'Bearer ',
  '/auth/',
  '/client/',
  '/preview/',
  '/ui/',
  'application/json'
)

function Is-AllowedLiteral {
  param(
    [string]$s
  )

  if ($null -eq $s) { return $true }
  $v = [string]$s

  if ([string]::IsNullOrWhiteSpace($v)) { return $true }

  foreach ($a in $allowedExact) {
    if ($v -eq $a) { return $true }
    if ($v.Contains($a)) { return $true }
  }

  # Allow i18n keys / identifiers / enums.
  if ($v -match '^[A-Z0-9_]{2,64}$') { return $true }
  if ($v -match '^[A-Za-z0-9_.-]{2,128}$' -and $v.Contains('.')) { return $true }

  # Allow URLs.
  if ($v -match '^(https?://|/)[A-Za-z0-9_\-./?=&%]+$') { return $true }

  return $false
}

function Add-Hit {
  param(
    [string]$file,
    [int]$line,
    [string]$term,
    [string]$snippet
  )

  $script:hits += [pscustomobject]@{
    File    = $file
    Line    = $line
    Term    = $term
    Snippet = $snippet
  }
}

function Strip-TemplateVars {
  param([string]$s)
  if ($null -eq $s) { return '' }
  return ([Regex]::Replace($s, '(?s)\{\{[^}]+\}\}', ''))
}

function Strip-I18nAttrs {
  param([string]$s)
  if ($null -eq $s) { return '' }
  $x = $s
  $x = [Regex]::Replace($x, '(?i)\sdata-i18n-(key|placeholder)\s*=\s*("[^"]*"|''[^'']*'')', '')
  return $x
}

function Strip-HtmlTags {
  param([string]$s)
  if ($null -eq $s) { return '' }
  $x = [Regex]::Replace($s, '(?s)<[^>]+>', ' ')
  $x = [Regex]::Replace($x, '\s+', ' ').Trim()
  return $x
}

function Scan-HtmlFile {
  param([string]$path)

  $lines = Get-Content -Path $path -Encoding UTF8

  $inScript = $false
  $inStyle = $false

  for ($i = 0; $i -lt $lines.Count; $i++) {
    $ln = $i + 1
    $line = $lines[$i]

    $lower = $line.ToLowerInvariant()

    # crude state machine to skip <script>/<style> blocks
    if (-not $inScript -and $lower -match '<script\b') { $inScript = $true }
    if (-not $inStyle -and $lower -match '<style\b') { $inStyle = $true }

    if ($inScript) {
      if ($lower -match '</script>') { $inScript = $false }
      continue
    }

    if ($inStyle) {
      if ($lower -match '</style>') { $inStyle = $false }
      continue
    }

    # Drop HTML comments (single-line best effort)
    $scan = [Regex]::Replace($line, '<!--.*?-->', '')

    $scan = Strip-I18nAttrs (Strip-TemplateVars $scan)

    # Extract user-visible attributes
    $attrs = @()
    foreach ($m in [Regex]::Matches($scan, '(?i)\b(title|placeholder|aria-label|alt|value)\s*=\s*"([^"]*)"')) {
      $attrs += $m.Groups[2].Value
    }
    foreach ($m in [Regex]::Matches($scan, "(?i)\\b(title|placeholder|aria-label|alt|value)\\s*=\\s*'([^']*)'")) {
      $attrs += $m.Groups[2].Value
    }

    $text = Strip-HtmlTags $scan

    $combined = @($text) + $attrs

    foreach ($part in $combined) {
      if ([string]::IsNullOrWhiteSpace($part)) { continue }

      foreach ($m in [Regex]::Matches($part, $pattern)) {
        $snippet = $part
        if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) }
        Add-Hit -file $path -line $ln -term $m.Value -snippet $snippet
      }
    }
  }
}

function Extract-JsStringLiterals {
  param([string]$line)

  $results = @()
  if ($null -eq $line) { return $results }

  # "..." and '...'
  foreach ($m in [Regex]::Matches($line, '"([^"\\]*(?:\\.[^"\\]*)*)"')) {
    $results += $m.Groups[1].Value
  }
  foreach ($m in [Regex]::Matches($line, "'([^'\\]*(?:\\.[^'\\]*)*)'")) {
    $results += $m.Groups[1].Value
  }

  # `...` (template literals) – best effort, may miss multi-line templates
  foreach ($m in [Regex]::Matches($line, '`([^`\\]*(?:\\.[^`\\]*)*)`')) {
    $results += $m.Groups[1].Value
  }

  return $results
}

function Scan-JsFile {
  param([string]$path)

  $lines = Get-Content -Path $path -Encoding UTF8

  $inBlockComment = $false

  for ($i = 0; $i -lt $lines.Count; $i++) {
    $ln = $i + 1
    $line = $lines[$i]

    # crude block comment skipping
    if (-not $inBlockComment -and $line -match '/\*') { $inBlockComment = $true }
    if ($inBlockComment) {
      if ($line -match '\*/') { $inBlockComment = $false }
      continue
    }

    $literals = Extract-JsStringLiterals $line
    foreach ($lit in $literals) {
      $s = $lit

      if (Is-AllowedLiteral $s) { continue }

      foreach ($m in [Regex]::Matches($s, $pattern)) {
        $snippet = $s
        if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0, 160) }
        Add-Hit -file $path -line $ln -term $m.Value -snippet $snippet
      }
    }
  }
}

$targets = @(
  (Join-Path $repoRoot 'src/api/src/main/resources/static/ui'),
  (Join-Path $repoRoot 'src/api/src/main/resources/templates')
)

$script:hits = @()

# HTML scan
foreach ($dir in $targets) {
  if (-not (Test-Path $dir)) { continue }
  $htmlFiles = Get-ChildItem -Path $dir -Recurse -File -Filter '*.html'
  foreach ($f in $htmlFiles) {
    Scan-HtmlFile -path $f.FullName
  }
}

# JS scan (UI only)
$uiDir = (Join-Path $repoRoot 'src/api/src/main/resources/static/ui')
if (Test-Path $uiDir) {
  $jsFiles = Get-ChildItem -Path $uiDir -Recurse -File -Filter '*.js'
  foreach ($f in $jsFiles) {
    Scan-JsFile -path $f.FullName
  }
}

if ($hits.Count -gt 0) {
  Write-Host ("Found English UI words that must be localized: {0} hit(s)" -f $hits.Count) -ForegroundColor Red

  foreach ($h in $hits) {
    # Grep-like format for CI logs: file:line
    Write-Host ("{0}:{1}: term='{2}'" -f $h.File, $h.Line, $h.Term) -ForegroundColor Yellow
    if ($VerboseOutput) {
      Write-Host ("  snippet: {0}" -f $h.Snippet)
    }
  }

  exit 1
}

Write-Host 'OK: no common English UI words found.' -ForegroundColor Green
