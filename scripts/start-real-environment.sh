#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIRECTORY/.." && pwd -P)"

exec sh "$REPOSITORY_ROOT/build-support/scripts/invoke-python.sh" "$SCRIPT_DIRECTORY/real_environment.py" "$@"
