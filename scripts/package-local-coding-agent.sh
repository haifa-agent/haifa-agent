#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH= cd -- "${script_dir}/.." && pwd -P)

exec sh "$repository_root/build-support/scripts/invoke-python.sh" "${script_dir}/package-local-coding-agent.py" "$@"
