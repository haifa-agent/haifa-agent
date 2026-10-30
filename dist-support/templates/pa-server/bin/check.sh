#!/bin/sh
# 自检：用临时端口与临时数据目录启动一次，探活成功即退出（不触碰 data/ 与正式端口）
set -eu

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
PORT=${1:-20999}
TMP_DIR=$(mktemp -d)
PID=""

cleanup() {
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill "$PID" 2>/dev/null || true
        wait "$PID" 2>/dev/null || true
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

# 服务要求显式确认 trustedHost 才会启动（fail closed）；自检只探活、不产生任何 run，
# 因此不会触发实际的宿主执行，数据目录也指向临时目录。
HAIFA_PERSONAL_DATA_DIR="$TMP_DIR/data" \
HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true \
    java -jar "$APP_HOME/app.jar" \
        --server.address=127.0.0.1 \
        --server.port="$PORT" \
        --haifa.personal.data-directory="$TMP_DIR/data" \
        >"$TMP_DIR/server.log" 2>&1 &
PID=$!

attempt=0
while [ "$attempt" -lt 90 ]; do
    if curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
        echo "[ok] app.jar 在 127.0.0.1:$PORT 启动并通过 /actuator/health 检查"
        exit 0
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "[error] 服务在变为健康前退出："
        tail -n 40 "$TMP_DIR/server.log"
        exit 1
    fi
    attempt=$((attempt + 1))
    sleep 1
done

echo "[error] 健康检查超时（90s）："
tail -n 40 "$TMP_DIR/server.log"
exit 1
