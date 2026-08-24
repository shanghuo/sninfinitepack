#!/usr/bin/env bash
echo "==> 跟随重定向"
curl -sIL --max-time 30 "https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-universal.jar" | grep -iE "^(HTTP|location)"
echo ""
echo "==> 尝试 maven.minecraftforge.net 的替代路径"
for u in \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-universal.jar" \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-installer.jar" \
  ; do
  code=$(curl -sIL --max-time 30 "$u" -o /dev/null -w "%{http_code}")
  echo "$code  $u"
done
