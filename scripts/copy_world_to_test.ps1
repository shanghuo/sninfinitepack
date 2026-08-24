# Copy the world saves (and map caches) from the LIVE instance into the TEST instance
# so the test copy is a direct full copy including the player's world.
# Game is running: copy twice with robocopy to re-copy files that changed mid-copy.
$ErrorActionPreference = "Continue"

$srcMc = "c:\project\mc\nw-mc-20251224\instances\GT_New_Horizons_2.8.4_Java_17-25\.minecraft"
$dstMc = "c:\project\mc\nw-mc-20251224\instances\GT_New_Horizons_2.8.4_InfPack_Test\.minecraft"

foreach ($d in @("saves", "journeymap", "visualprospecting", "backups")) {
    $s = Join-Path $srcMc $d
    $t = Join-Path $dstMc $d
    if (Test-Path $s) {
        Write-Host "==> Copying $d (pass 1)..."
        robocopy $s $t /E /R:1 /W:1 /NFL /NDL /NP | Out-Host
        Write-Host "==> Copying $d (pass 2 - re-copy changed)..."
        robocopy $s $t /E /R:1 /W:1 /NFL /NDL /NP | Out-Host
        $code = $LASTEXITCODE
        Write-Host "robocopy exit: $code"
    } else {
        Write-Host "==> Source missing, skip: $s"
    }
}

Write-Host "==> Test instance .minecraft dirs now:"
Get-ChildItem $dstMc -Directory | Select-Object Name | Out-String
Write-Host "==> Done"
