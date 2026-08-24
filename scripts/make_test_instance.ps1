# Copy the live GTNH instance to a TEST instance with the backpack mod installed.
# The live (playing) instance is only read, never modified.
# Excludes actively-written dirs (world saves, logs, backups, screenshots, player map caches)
# so the copy is safe to take while the game is running.
$ErrorActionPreference = "Continue"

$src = "c:\project\mc\nw-mc-20251224\instances\GT_New_Horizons_2.8.4_Java_17-25"
$dst = "c:\project\mc\nw-mc-20251224\instances\GT_New_Horizons_2.8.4_InfPack_Test"
$mod = "c:\project\mc\docker\dist\infinitepack-1.0.0.jar"
$excludeDirs = @("saves", "logs", "backups", "screenshots", "journeymap", "visualprospecting")
$excludeFiles = @("hs_err_*.log", ".healer.log")

Write-Host "==> Source: $src"
Write-Host "==> Dest:   $dst"

if (Test-Path "$dst\.minecraft") {
    Write-Host "Target already exists, skip copy (avoid duplication)"
} else {
    Write-Host "==> robocopy (excluding active dirs)..."
    robocopy $src $dst /E /R:1 /W:1 /XD $excludeDirs /XF $excludeFiles /NFL /NDL /NP | Out-Host
    Write-Host "robocopy exit code: $LASTEXITCODE (0-7 = success)"
}

# Rename instance display name
$cfg = Join-Path $dst "instance.cfg"
if (Test-Path $cfg) {
    $content = Get-Content $cfg -Raw
    $content = $content -replace "(?m)^name=.*", "name=GT_New_Horizons_2.8.4_InfPack_Test"
    Set-Content -Path $cfg -Value $content -Encoding Ascii
    Write-Host "==> instance.cfg name updated"
}

# Install the mod
$modsDir = Join-Path $dst ".minecraft\mods"
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
Copy-Item $mod $modsDir -Force
Write-Host "==> Mod installed to: $modsDir"

Get-ChildItem $modsDir -Filter "infinitepack*" | Select-Object Name, Length | Out-String
Write-Host "==> Done"
