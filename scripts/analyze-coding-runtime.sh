#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
python_script="${script_dir}/analyze_coding_runtime.py"

if [ -n "${HAIFA_PYTHON_EXECUTABLE:-}" ]; then
    python_executable=$HAIFA_PYTHON_EXECUTABLE
elif command -v python3 >/dev/null 2>&1; then
    python_executable=python3
elif command -v python >/dev/null 2>&1; then
    python_executable=python
else
    printf '%s\n' "Python 3 is required. Set HAIFA_PYTHON_EXECUTABLE or add python3 to PATH." >&2
    exit 1
fi

exec "$python_executable" "$python_script" "$@"
