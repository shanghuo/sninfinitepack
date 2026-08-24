# 1) Remove the test instance I previously created inside the LIVE nw-mc-20251224 dir
#    so the live dir is 100% clean (no mod, no test instance).
# 2) Full-copy nw-mc-20251224 -> nw-mc-20251224-test (a brand new separate directory).
# 3) Install the mod into the copied instance.
# 4) Tune memory for the test launch.
$ErrorActionPreference = "Continue"

$testInst = "c:\project\mc\nw-mc-20251224\instances\GT_New_Horizons_2.8.4_InfPack_Test"
if (Test-Path $testInst) {
    Write-Host "==> Removing test instance from LIVE dir..."
    Remove-Item $testInst -Recurse -Force
    Write-Host "    removed: $testInst"
} else {
    Write-Host "==> No test instance in LIVE dir (already clean)"
}

$src = "c:\project\mc\nw-mc-20251224"
$dst = "c:\project\mc\nw-mc-20251224-test"

if (Test-Path "$dst\prismlauncher.exe") {
    Write-Host "==> Destination already exists, skip full copy"
} else {
    Write-Host "==> Full copy: $src  ->  $dst (this is a separate new directory)"
    robocopy $src $dst /E /R:1 /W:1 /NFL /NDL /NP | Out-Host
    Write-Host "    robocopy exit: $LASTEXITCODE (0-7 = success)"
}

# Install mod into the copied instance
$copyMods = "$dst\instances\GT_New_Horizons_2.8.4_Java_17-25\.minecraft\mods"
if (-not (Test-Path $copyMods)) { New-Item -ItemType Directory -Force -Path $copyMods | Out-Null }
Copy-Item "c:\project\mc\docker\dist\infinitepack-1.0.0.jar" $copyMods -Force
Write-Host "==> Mod installed to: $copyMods"
Get-ChildItem $copyMods -Filter "infinitepack*" | Select-Object Name, Length | Out-String

# Memory tuning for the test launch
$cfg = "$dst\instances\GT_New_Horizons_2.8.4_Java_17-25\instance.cfg"
if (Test-Path $cfg) {
    $c = Get-Content $cfg -Raw
    $c = $c -replace "(?m)^MinMemAlloc=.*", "MinMemAlloc=1024"
    $c = $c -replace "(?m)^MaxMemAlloc=.*", "MaxMemAlloc=4096"
    Set-Content -Path $cfg -Value $c -Encoding Ascii
    Write-Host "==> Memory set: Min=1024 Max=4096"
    (Get-Content $cfg | Select-String "MemAlloc") | ForEach-Object { $_.Line } | Out-String
}

Write-Host "==> Done. Test dir ready: $dst"
