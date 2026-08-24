#!/usr/bin/env bash
# 探测 maven 上标准 Forge 1.7.10 各版本的 universal jar
for v in "1.7.10-10.13.4.1558" "1.7.10-10.13.4.1557" "1.7.10-10.13.4.1566" "1.7.10-10.13.4.1614"; do
  for base in "https://maven.minecraftforge.net/net/minecraftforge/forge" \
              "https://maven.minecraftforge.net/releases/net/minecraftforge/forge"; do
    u="$base/$v/forge-$v-universal.jar"
    code=$(curl -sIL --max-time 20 "$u" -o /dev/null -w "%{http_code}")
    if [ "$code" != "404" ]; then
      echo "FOUND $code  $u"
    fi
  done
done
echo "== done =="
