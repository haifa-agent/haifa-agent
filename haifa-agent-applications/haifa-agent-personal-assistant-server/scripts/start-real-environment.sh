#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if command -v python3 >/dev/null 2>&1; then
    PYTHON_COMMAND="$(command -v python3)"
elif command -v python >/dev/null 2>&1; then
    PYTHON_COMMAND="$(command -v python)"
else
    printf 'Error: Python 3 was not found on PATH.\n' >&2
    exit 1
fi

exec "$PYTHON_COMMAND" "$SCRIPT_DIRECTORY/real_environment.py" "$@"
