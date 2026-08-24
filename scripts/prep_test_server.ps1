# Prepare a test server directory under docker/test/server from the LIVE instance.
# READ-ONLY source: the live (playing) instance is never modified.
$ErrorActionPreference = "Stop"

$instance = "c:\project\mc\nw-mc-20251224\instances\GT_New_Horizons_2.8.4_Java_17-25"
$server = "c:\project\mc\docker\test\server"
$vanillaLib = "c:\project\mc\nw-mc-20251224\libraries\com\mojang\minecraft\1.7.10\minecraft-1.7.10-client.jar"

Write-Host "==> Preparing server dir: $server"
New-Item -ItemType Directory -Force -Path $server | Out-Null

Write-Host "==> Copy forge universal jar"
Copy-Item "$instance\libraries\net\minecraftforge\forge\1.7.10-10.13.4.1614-1.7.10\forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" $server -Force

Write-Host "==> Copy minecraft 'server' jar (1.7.10 client jar contains server classes)"
Copy-Item $vanillaLib "$server\minecraft_server.1.7.10.jar" -Force

Write-Host "==> Copy libraries (launcher-level + instance-level, excluding lwjgl3ify client patch)"
if (-not (Test-Path "$server\libraries")) {
    New-Item -ItemType Directory -Force -Path "$server\libraries" | Out-Null
    Copy-Item "c:\project\mc\nw-mc-20251224\libraries\*" "$server\libraries" -Recurse -Force
    Copy-Item "$instance\libraries\*" "$server\libraries" -Recurse -Force
    $exclude = "$server\libraries\lwjgl3ify-2.1.16-forgePatches.jar"
    if (Test-Path $exclude) { Remove-Item $exclude -Force }
} else {
    Write-Host "    libraries already present, skipped"
}

Write-Host "==> Create mods/ (minimal test: only our mod goes here)"
New-Item -ItemType Directory -Force -Path "$server\mods" | Out-Null

Write-Host "==> Done"
