#!/usr/bin/env bash
# 检查发布 jar 字节码引用的方法名（验证运行时 SRG 名）
set -e
cd /work/dist
JAR="$1"
if [ -z "$JAR" ]; then JAR="infinitepack-1.1.0.jar"; fi
javap -c -p -classpath "$JAR" com.infpack.BackpackStorage > /tmp/javap_bp2.txt 2>&1
echo "==> $JAR BackpackStorage 引用的 NBT 方法:"
grep -oE 'func_[0-9a-z_]+|setString|setInteger|setTag|getString|getInteger|getCompoundTag|getTagList|appendTag|tagCount|getCompoundTagAt|hasKey|getTag|setTagCompound|getTagCompound|hasTagCompound|getNameForObject|getObject' /tmp/javap_bp2.txt | sort | uniq -c | sort -rn | head -30
echo "==> 是否还有 MCP 名残留（应为空）:"
grep -E '// Method net/minecraft/(nbt|item)/[A-Za-z]+\.(setString|getString|getKeySet|hasKey|getTag|tagCount|appendTag|getCompoundTagAt)' /tmp/javap_bp2.txt | head -10
echo "== done =="
