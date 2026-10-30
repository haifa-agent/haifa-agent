#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

exec sh "$SCRIPT_DIR/invoke-python.sh" "$SCRIPT_DIR/codebase_stats.py" "$@"
