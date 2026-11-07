#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$SCRIPT_DIR/../build-support/scripts/invoke-python.sh" "$SCRIPT_DIR/publish-local-sdk.py" "$@"
