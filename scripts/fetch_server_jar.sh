#!/usr/bin/env bash
# 下载真正的 minecraft_server.1.7.10.jar（Mojang 官方）
set -e
apt-get install -y -qq jq >/dev/null 2>&1 || true

cd /work/test/server
rm -f minecraft_server.1.7.10.jar

echo "==> 获取 1.7.10 版本清单"
MANIFEST=$(curl -fsSL "https://piston-meta.mojang.com/mc/game/version_manifest.json")
VURL=$(echo "$MANIFEST" | jq -r '.versions[] | select(.id=="1.7.10") | .url')
echo "   版本URL: $VURL"
SURL=$(curl -fsSL "$VURL" | jq -r '.downloads.server.url')
echo "   serverURL: $SURL"

echo "==> 下载 minecraft_server.1.7.10.jar"
curl -fsSL -o minecraft_server.1.7.10.jar "$SURL"
ls -la minecraft_server.1.7.10.jar
sha1sum minecraft_server.1.7.10.jar
echo "==> 完成"
