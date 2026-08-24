# 检查发布 jar 的字节码引用的方法名（验证是否 SRG 名）
param([string]$jar = "c:\project\mc\docker\dist\infinitepack-1.0.0.jar", [string]$cls = "com.infpack.BackpackStorage")
$javap = "C:\Program Files\Java\jdk-24\bin\javap.exe"
$out = & $javap -c -p -classpath $jar $cls 2>&1 | Out-String
$lines = $out -split "`n" | Where-Object { $_ -match "Method (setString|setInteger|setTag|getString|getInteger|getCompoundTag|getTagList|appendTag|tagCount|getCompoundTagAt|hasKey|getTag|func_150|setTagCompound|getTagCompound|hasTagCompound)" }
$lines | Select-Object -First 40
Write-Output "--- 汇总: 含 func_ 的行数 = $($lines | Where-Object { $_ -match 'func_' } | Measure-Object | Select-Object -ExpandProperty Count)"
Write-Output "--- 含 MCP 名(setString 等) 的行数 = $($lines | Where-Object { $_ -match 'Method (setString|getString|setInteger|getInteger|hasKey|getTag|tagCount|appendTag)' } | Measure-Object | Select-Object -ExpandProperty Count)"
