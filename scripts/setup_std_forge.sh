#!/usr/bin/env bash
# 切换为标准 Forge 1.7.10 服务器：下载标准 universal jar，移除 GTNH forge jar 与客户端 jar
set -e
cd /work/test/server

# 1) 下载标准 Forge 1.7.10-10.13.4.1614 universal jar
echo "==> 下载标准 Forge 1.7.10 universal jar"
STD_FORGE="forge-1.7.10-10.13.4.1614-universal.jar"
curl -fsSL -o "$STD_FORGE" \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-universal.jar" \
  || echo "  !! 下载失败，检查 URL"
ls -la "$STD_FORGE" 2>/dev/null || true

# 2) 移除 GTNH forge universal jar（改用标准版）
rm -f forge-1.7.10-10.13.4.1614-1.7.10-universal.jar

# 3) 从 libraries 移除客户端 jar（服务端 classpath 只需 server jar）
rm -f libraries/com/mojang/minecraft/1.7.10/minecraft-1.7.10-client.jar

echo "==> 当前服务器目录 jar："
ls -la *.jar 2>/dev/null
echo "==> 完成"
