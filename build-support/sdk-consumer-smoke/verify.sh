#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

"$repo_root/mvnw" --batch-mode --no-transfer-progress -f "$script_dir/maven/pom.xml" verify
"$repo_root/mvnw" --batch-mode --no-transfer-progress -f "$script_dir/spring-boot/pom.xml" verify

if ! command -v gradle >/dev/null 2>&1; then
    echo "A current Gradle installation is required for the Gradle consumer smoke." >&2
    exit 1
fi
gradle --no-daemon --console=plain -p "$script_dir/gradle" clean build
