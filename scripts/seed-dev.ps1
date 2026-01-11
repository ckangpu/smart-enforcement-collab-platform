$ErrorActionPreference = "Stop"

# Ensure piping to external commands uses UTF-8 (important for SQL with non-ASCII text).
$OutputEncoding = [System.Text.UTF8Encoding]::new()

# scripts/seed-dev.ps1
# DEV ONLY: import local seed data into the Postgres container for API Quickstart.

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$SeedPath = Join-Path $RepoRoot "db\seeds\dev_seed.sql"

if (-not (Test-Path $SeedPath)) {
  throw "Seed SQL not found: $SeedPath"
}

Write-Host "Seeding local dev data from: $SeedPath"

# Use stdin pipe (PowerShell-friendly). The -T flag disables TTY allocation so piping works.
# Run psql through sh inside the container so we can set PGPASSWORD from container env.
Get-Content -Raw -Encoding utf8 $SeedPath | docker compose exec -T postgres sh -lc 'PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

if ($LASTEXITCODE -ne 0) {
  throw "psql failed with exit code $LASTEXITCODE"
}

Write-Host "Done. Dev seed imported." 
