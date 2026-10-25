#!/bin/sh
# 前台启动 Haifa Personal Assistant Server（生产环境请用 conf/haifa-personal-assistant.service）
set -eu

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${PA_ENV_FILE:-$APP_HOME/conf/pa.env}

if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    . "$ENV_FILE"
fi

: "${HAIFA_PERSONAL_DATA_DIR:=$APP_HOME/data}"
: "${HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED:=false}"
export HAIFA_PERSONAL_DATA_DIR HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED

mkdir -p "$HAIFA_PERSONAL_DATA_DIR" "$APP_HOME/logs"

exec java -Xmx1024m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8 \
    -jar "$APP_HOME/app.jar" \
    --server.address=127.0.0.1 \
    --server.port="${PA_PORT:-20001}" \
    --haifa.personal.data-directory="$HAIFA_PERSONAL_DATA_DIR" \
    --logging.file.name="$APP_HOME/logs/personal-assistant.log" \
    "$@"
