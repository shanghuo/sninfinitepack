#!/usr/bin/env bash
echo "==> Forge maven-metadata 中的 1.7.10 版本:"
curl -fsSL --max-time 40 "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml" \
  | grep -oE "1\.7\.10-[0-9.]+" | sort -u | tail -20
echo ""
echo "==> promotions_slim.json 中的 1.7.10:"
curl -fsSL --max-time 40 "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json" \
  | grep -oE '"1\.7\.10-[^"]*"' | sort -u | tail -20
