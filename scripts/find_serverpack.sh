#!/usr/bin/env bash
echo "==> Server install guide 原文中的下载链接:"
curl -fsSL --max-time 40 "https://wiki.gtnewhorizons.com/wiki/Server_install_guide" \
  | grep -oE 'https?://[^"<> ]*(zip|Server|server)[^"<> ]*' | sort -u | head -30
echo ""
echo "==> Downloads 页:"
curl -fsSL --max-time 40 "https://wiki.gtnewhorizons.com/wiki/Downloads" \
  | grep -oE 'https?://[^"<> ]*' | grep -iE 'download|server|pack' | sort -u | head -30
