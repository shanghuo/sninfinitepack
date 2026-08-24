#!/usr/bin/env bash
# 在容器内启动 Forge 1.7.10 测试服务器，执行 /infpacktest 自动化验证，抓取结果后关闭。
set -u
cd /work/test/server

echo "eula=true" > eula.txt
mkdir -p mods
if [ ! -f mods/infinitepack-1.0.0.jar ]; then
  cp /work/dist/infinitepack-1.0.0.jar mods/
fi

rm -f server.out
rm -f /tmp/mcstdin
mkfifo /tmp/mcstdin

echo "==> 启动 Forge 1.7.10 测试服务器（JDK8）"
# 显式构建完整 classpath（libraries 全部 jar + minecraft_server + forge universal）
CP=$(find "$PWD/libraries" -name '*.jar' | tr '\n' ':')
CP="${CP}minecraft_server.1.7.10.jar:forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"
/opt/jdk8/bin/java \
  -Xms1G -Xmx3G \
  -Dfml.queryResult=confirm \
  -Dfml.ignoreInvalidMinecraftCertificates=true \
  -Dfml.ignorePatchDiscrepancies=true \
  -Duser.language=en -Duser.country=US \
  -cp "$CP" cpw.mods.fml.relauncher.ServerLaunchWrapper nogui < /tmp/mcstdin > server.out 2>&1 &
SERVER_PID=$!

# 打开写端（java 已打开读端）
exec 3>/tmp/mcstdin

echo "==> 等待服务器就绪 (Done) ..."
READY=0
for i in $(seq 1 300); do
  if grep -q "Done (" server.out 2>/dev/null; then
    READY=1
    break
  fi
  if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "!! 服务器进程提前退出"
    break
  fi
  sleep 2
done

if [ "$READY" != "1" ]; then
  echo "==> 服务器未就绪，输出尾部："
  tail -60 server.out
  kill $SERVER_PID 2>/dev/null
  wait $SERVER_PID 2>/dev/null
  exit 1
fi

echo "==> 服务器就绪，执行 /infpacktest"
echo "infpacktest" >&3
sleep 25

echo "==> 请求服务器停止"
echo "stop" >&3
sleep 15

exec 3>&-
wait $SERVER_PID 2>/dev/null

echo ""
echo "==================== 测试结果（来自日志） ===================="
grep -aE "\[infpacktest\]|InfinitePack 测试|INFINITEPACK" server.out | tail -60
echo "============================================================="
echo "==> DONE"
