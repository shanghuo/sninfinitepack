#!/usr/bin/env bash
set -e
apt-get update -qq >/dev/null 2>&1 || true
apt-get install -y -qq curl >/dev/null 2>&1 || true
check() {
  local u="$1"
  local code
  code=$(curl -sI --max-time 40 "$u" -o /dev/null -w '%{http_code}')
  echo "$u -> $code"
}
check "https://services.gradle.org/distributions/"
check "https://nexus.gtnewhorizons.com/"
check "https://maven.minecraftforge.net/"
check "https://api.adoptium.net/"
check "https://repo.maven.apache.org/maven2/"
check "https://api.foojay.io/"
echo "NETTEST_DONE"
