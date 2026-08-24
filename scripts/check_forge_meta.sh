#!/usr/bin/env bash
echo "==> 1.7.10-10.13.4.1614 的 maven-metadata:"
curl -fsSL --max-time 40 "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614/maven-metadata.xml" | head -60
echo ""
echo "==> 尝试直接下载（带 -L）:"
curl -fsSL --max-time 60 -o /tmp/forge-std.jar \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-universal.jar" \
  && ls -la /tmp/forge-std.jar && echo "OK" || echo "FAIL"
