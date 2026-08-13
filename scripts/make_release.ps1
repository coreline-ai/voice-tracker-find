<#
.SYNOPSIS
    Build a privacy-safe distributable zip of AI R Voice for other people.

.DESCRIPTION
    Uses an ALLOWLIST: only named, known-safe items are copied into the release.
    Personal data (vault, .env, receiver token, recordings, DB, .claude/memory,
    users.json) lives outside this list and is never bundled. A final secret scan
    aborts if anything sensitive slipped into the staging area.

    Recipients get: source + SETUP.md (Claude runbook) + DISTRIBUTE.md + the APK.
    They install Claude Code, open it here, and say "follow SETUP.md".

.PARAMETER Output
    Path of the zip to create. Default: <repo-parent>\ai-r-voice-release.zip

.PARAMETER Apk
    Path to the phone APK to bundle. Default: ~/.airvoice/ai-r-voice.apk
#>
param(
    [string]$Output,
    [string]$Apk = (Join-Path $HOME ".airvoice\ai-r-voice.apk")
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Output) { $Output = Join-Path (Split-Path -Parent $RepoRoot) "ai-r-voice-release.zip" }

# Only these are copied. Everything else (vault, .env, tokens, recordings, DB) is excluded by omission.
$AllowFiles = @("pyproject.toml", "README.md", "SETUP.md", "DISTRIBUTE.md", ".env.example")
$AllowDirs  = @("src", "scripts", "tests")
# .claude: ship generic tooling only; never personal/stateful subfolders.
$ClaudeKeep = @("agents", "commands", "skills")

$Staging = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-r-voice-rel-" + [System.IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Force -Path $Staging | Out-Null

function Copy-Clean($src, $dstParent) {
    if (-not (Test-Path $src)) { return }
    $dst = Join-Path $dstParent (Split-Path -Leaf $src)
    Copy-Item -Path $src -Destination $dst -Recurse
}

Write-Host "Staging into $Staging ..." -ForegroundColor Cyan
foreach ($f in $AllowFiles) { Copy-Clean (Join-Path $RepoRoot $f) $Staging }
foreach ($d in $AllowDirs)  { Copy-Clean (Join-Path $RepoRoot $d) $Staging }

# .claude: copy only the generic subfolders.
$claudeSrc = Join-Path $RepoRoot ".claude"
if (Test-Path $claudeSrc) {
    $claudeDst = Join-Path $Staging ".claude"
    New-Item -ItemType Directory -Force -Path $claudeDst | Out-Null
    foreach ($sub in $ClaudeKeep) { Copy-Clean (Join-Path $claudeSrc $sub) $claudeDst }
}

# APK (built via GitHub Actions, copied to ~/.airvoice by the release flow).
if (Test-Path $Apk) {
    Copy-Item $Apk (Join-Path $Staging "ai-r-voice.apk")
} else {
    Write-Warning "APK not found at $Apk — release will omit it. Phone can still fetch via /apk."
}

# Strip build/cache junk that may ride along inside copied dirs.
Get-ChildItem -Path $Staging -Recurse -Directory -Include "__pycache__", ".pytest_cache" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

# --- Defense in depth: abort if anything sensitive is in the staging area ---
$badNames = Get-ChildItem -Path $Staging -Recurse -File | Where-Object {
    $_.Name -eq ".env" -or $_.Name -eq "users.json" -or
    $_.Extension -in ".m4a", ".mp3", ".wav", ".db" -or
    $_.Name -like "*receiver-token*" -or $_.Name -like "*_original.txt"
}
if ($badNames) {
    Write-Host "ABORT: sensitive files reached staging:" -ForegroundColor Red
    $badNames | ForEach-Object { Write-Host "  $($_.FullName)" -ForegroundColor Red }
    Remove-Item -Recurse -Force $Staging
    exit 1
}

if (Test-Path $Output) { Remove-Item -Force $Output }
Compress-Archive -Path (Join-Path $Staging "*") -DestinationPath $Output
Remove-Item -Recurse -Force $Staging

$sizeMB = [math]::Round((Get-Item $Output).Length / 1MB, 1)
Write-Host "Built $Output ($sizeMB MB)" -ForegroundColor Green
Write-Host "REVIEW before sharing: unzip and confirm no personal data (esp. under .claude/)." -ForegroundColor Yellow
