#!/usr/bin/env bash
# 检查发布 jar 字节码引用的方法名（验证运行时 SRG 名）
set -e
cd /work/dist
javap -c -p -classpath infinitepack-1.0.0.jar com.infpack.BackpackStorage > /tmp/javap_bp.txt 2>&1
echo "==> BackpackStorage 引用的 NBT 方法:"
grep -oE 'func_[0-9a-z_]+|setString|setInteger|setTag|getString|getInteger|getCompoundTag|getTagList|appendTag|tagCount|getCompoundTagAt|hasKey|getTag|setTagCompound|getTagCompound|hasTagCompound|getNameForObject|getObject' /tmp/javap_bp.txt | sort | uniq -c | sort -rn | head -40
echo "==> 样例行:"
grep -E '// (Method|Field)' /tmp/javap_bp.txt | head -25
