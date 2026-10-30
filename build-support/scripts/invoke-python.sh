#!/usr/bin/env sh
# Copyright 2026 Haifa Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
#
# Runs a repository Python 3 entry script with the shared interpreter resolution:
# HAIFA_PYTHON_EXECUTABLE first, then python3, then python. Arguments are passed
# through unchanged and the child exit code is preserved through exec.

set -eu

python_script="${1:?Python entry script path is required}"
shift

if [ -n "${HAIFA_PYTHON_EXECUTABLE:-}" ]; then
    exec "$HAIFA_PYTHON_EXECUTABLE" "$python_script" "$@"
elif command -v python3 >/dev/null 2>&1; then
    exec python3 "$python_script" "$@"
elif command -v python >/dev/null 2>&1; then
    exec python "$python_script" "$@"
fi

printf '%s\n' "Python 3 is required. Set HAIFA_PYTHON_EXECUTABLE or add python3 to PATH." >&2
exit 1
