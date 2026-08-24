#!/usr/bin/env bash
# 探测 GTNH 服务器整合包下载 URL
for u in \
  "https://downloads.gtnewhorizons.com/" \
  "https://downloads.gtnewhorizons.com/ServerPacks/GT_New_Horizons_2.8.4_Server_Java_17-21.zip" \
  "https://downloads.gtnewhorizons.com/GT_New_Horizons_2.8.4_Server_Java_17-21.zip" \
  "https://downloads.gtnewhorizons.com/server/GT_New_Horizons_2.8.4_Server_Java_17-21.zip" \
  ; do
  code=$(curl -sIL --max-time 30 "$u" -o /dev/null -w "%{http_code} %{url_effective}")
  echo "$code  $u"
done
