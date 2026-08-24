#!/usr/bin/env bash
# 完整构建模组 jar 并复制到 /work/dist
set -e
cd /work/mcmod
echo "==> gradlew build"
./gradlew build 2>&1 | grep -vE '^> Task|^$|Download|Resolve|Configuration' | tail -80
echo "==> build/libs:"
ls -la build/libs/ 2>&1
echo "==> 复制到 /work/dist"
mkdir -p /work/dist
cp build/libs/*.jar /work/dist/ 2>/dev/null || true
ls -la /work/dist/
echo "==> done"
