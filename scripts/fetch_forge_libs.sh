#!/usr/bin/env bash
# 下载标准 Forge 1.7.10 FML 运行库（launchwrapper/asm/commons-lang3）到测试服务器 libraries
set -e
cd /work/test/server/libraries
mkdir -p net/minecraft/launchwrapper/1.11 org/ow2/asm/asm-all/5.0.3 org/apache/commons/commons-lang3/3.2.1

# launchwrapper-1.11（Mojang / Forge 源）
if [ ! -s net/minecraft/launchwrapper/1.11/launchwrapper-1.11.jar ]; then
  for u in \
    "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.11/launchwrapper-1.11.jar" \
    "https://files.minecraftforge.net/maven/net/minecraft/launchwrapper/1.11/launchwrapper-1.11.jar" ; do
    echo "尝试 $u"
    if curl -fsSL -o net/minecraft/launchwrapper/1.11/launchwrapper-1.11.jar "$u"; then
      echo "  成功"
      break
    fi
  done
fi

# asm-all-5.0.3
curl -fsSL -o org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar \
  "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar" || true

# commons-lang3-3.2.1
curl -fsSL -o org/apache/commons/commons-lang3/3.2.1/commons-lang3-3.2.1.jar \
  "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.2.1/commons-lang3-3.2.1.jar" || true

echo "==> 结果:"
ls -la net/minecraft/launchwrapper/1.11 org/ow2/asm/asm-all/5.0.3 org/apache/commons/commons-lang3/3.2.1
