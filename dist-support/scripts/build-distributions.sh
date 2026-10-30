#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"

echo "[TIP] Packaging only collects prebuilt artifacts. Build the SDK modules first with: invoke-haifa-maven.sh --layer L2 '--' -pl <sdk-modules> -am -Prelease-artifacts verify (see dist-support/README.md)."

exec sh "$repository_root/build-support/scripts/invoke-python.sh" "$script_dir/package_distributions.py" "$@"
