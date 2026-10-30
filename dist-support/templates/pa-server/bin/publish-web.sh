#!/bin/sh
# 把包内 web/ 发布到 nginx 可读目录。
#
# 默认不复制任何文件：nginx 可以直接 root 到 <安装目录>/web，这样普通用户安装时
# 不需要任何 root 权限（推荐）。只有需要把静态文件放到别处时才设置 PA_WEB_ROOT：
#   PA_WEB_ROOT=/var/www/pa sh bin/publish-web.sh
set -eu

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
SOURCE="$APP_HOME/web"

if [ ! -f "$SOURCE/index.html" ]; then
    echo "[error] $SOURCE/index.html 不存在，包内缺少前端产物" >&2
    exit 1
fi

if [ -z "${PA_WEB_ROOT:-}" ]; then
    echo "[info] 未设置 PA_WEB_ROOT，未复制任何文件。"
    echo "       nginx 站点可直接使用：root $SOURCE;"
    echo "       如需复制到其它目录：PA_WEB_ROOT=/var/www/pa sh bin/publish-web.sh"
    exit 0
fi

mkdir -p "$PA_WEB_ROOT"
find "$PA_WEB_ROOT" -mindepth 1 -delete
cp -R "$SOURCE"/. "$PA_WEB_ROOT"/
chmod -R a+rX "$PA_WEB_ROOT"
echo "[ok] 已发布 $SOURCE -> $PA_WEB_ROOT"
