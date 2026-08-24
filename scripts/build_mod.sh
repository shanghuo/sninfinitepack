#!/usr/bin/env bash
# 编译模组源码并过滤输出
set -e
cd /work/mcmod
echo "==> compileJava"
./gradlew compileJava 2>&1 | grep -vE '^> Task|^$|Download|Resolve|Configuration' | tail -120
echo "==> exit: $?"
