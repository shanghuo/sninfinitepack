# Add a local OFFLINE account to the TEST copy's Prism accounts.json (with backup).
# The MSA (online) account is kept but set inactive, so the test uses the offline account.
$ErrorActionPreference = "Stop"

$path = "c:\project\mc\nw-mc-20251224-test\accounts.json"

# Backup
Copy-Item $path "$path.bak" -Force
Write-Host "==> Backup: $path.bak"

$j = Get-Content $path -Raw | ConvertFrom-Json

# Deactivate existing (MSA) accounts
foreach ($a in $j.accounts) { $a.active = $false }

# Add offline account
$off = [pscustomobject]@{
    active  = $true
    profile = [pscustomobject]@{ name = "TestPlayer" }
    type    = "offline"
}
$j.accounts += $off

$json = $j | ConvertTo-Json -Depth 20
[System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))

# Validate it parses back
$v = Get-Content $path -Raw | ConvertFrom-Json
Write-Host "==> 验证: 账号数 = $($v.accounts.Count)"
foreach ($a in $v.accounts) { Write-Host "    type=$($a.type) active=$($a.active) name=$($a.profile.name)" }
