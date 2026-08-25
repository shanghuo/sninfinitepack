#!/usr/bin/env bash
# 完整构建 1.7.10 模组 jar 并复制到 /work/dist（全部带 mc 版本后缀，与其它版本隔离）
set -e
cd /work/mcmod-1.7.10
echo "==> gradlew build"
./gradlew build 2>&1 | grep -vE '^> Task|^$|Download|Resolve|Configuration' | tail -80
echo "==> build/libs:"
ls -la build/libs/ 2>&1
echo "==> 复制到 /work/dist（jar 加 -mc1.7.10 后缀）"
mkdir -p /work/dist
for f in build/libs/*.jar; do
  [ -e "$f" ] || continue
  base=$(basename "$f" .jar)
  case "$base" in
    sninfinitepack-1.0.2)          cp "$f" /work/dist/sninfinitepack-1.0.2-mc1.7.10.jar ;;
    sninfinitepack-1.0.2-dev)      cp "$f" /work/dist/sninfinitepack-1.0.2-mc1.7.10-dev.jar ;;
    sninfinitepack-1.0.2-sources)  cp "$f" /work/dist/sninfinitepack-1.0.2-mc1.7.10-sources.jar ;;
    *)                             echo "跳过未知 jar: $base" ;;
  esac
done
rm -f /work/dist/sninfinitepack-1.0.2.jar /work/dist/sninfinitepack-1.0.2-dev.jar /work/dist/sninfinitepack-1.0.2-sources.jar
ls -la /work/dist/
echo "==> done"
