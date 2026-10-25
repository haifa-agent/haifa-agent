#!/bin/sh
# 把包内 web/ 发布到 nginx 可读目录；/root 权限是 700，nginx 无法穿越，所以不能直接 root 到包内。
set -eu

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
SOURCE="$APP_HOME/web"
TARGET=${PA_WEB_ROOT:-/var/www/pa}

if [ ! -f "$SOURCE/index.html" ]; then
    echo "[error] $SOURCE/index.html 不存在，包内缺少前端产物" >&2
    exit 1
fi

mkdir -p "$TARGET"
find "$TARGET" -mindepth 1 -delete
cp -R "$SOURCE"/. "$TARGET"/
chmod -R a+rX "$TARGET"
echo "[ok] 已发布 $SOURCE -> $TARGET"
