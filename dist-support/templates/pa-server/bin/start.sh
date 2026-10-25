#!/bin/sh
# 前台启动 Haifa Personal Assistant Server。
# 生产环境建议使用 conf/haifa-personal-assistant.service（不需要 root）。
set -eu

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${PA_ENV_FILE:-$APP_HOME/conf/pa.env}

if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    . "$ENV_FILE"
fi

: "${HAIFA_PERSONAL_DATA_DIR:=$APP_HOME/data/personal-assistant}"
: "${HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED:=true}"
export HAIFA_PERSONAL_DATA_DIR HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED

TMP_DIR="$HAIFA_PERSONAL_DATA_DIR/tmp"
mkdir -p "$HAIFA_PERSONAL_DATA_DIR" "$TMP_DIR" "$APP_HOME/logs"

# java.io.tmpdir 指向数据目录：sqlite-jdbc 会把原生库解压到这里再 dlopen，
# 避免依赖 /tmp 的 exec 权限（容器与 noexec 挂载下也能用）。
exec java -Xmx1024m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8 \
    -Djava.io.tmpdir="$TMP_DIR" \
    -jar "$APP_HOME/app.jar" \
    --server.address=127.0.0.1 \
    --server.port="${PA_PORT:-20001}" \
    --haifa.personal.data-directory="$HAIFA_PERSONAL_DATA_DIR" \
    --logging.file.name="$APP_HOME/logs/personal-assistant.log" \
    "$@"
