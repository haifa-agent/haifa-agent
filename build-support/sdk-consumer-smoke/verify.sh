#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"

exec sh "$script_dir/../scripts/invoke-python.sh" "$script_dir/verify.py" "$@"
