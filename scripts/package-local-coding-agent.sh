#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd -P)
cli_dir="${repo_dir}/haifa-agent-applications/haifa-agent-cli"
target_dir="${cli_dir}/target"
launcher_file="${cli_dir}/distribution/haifa-coding"
config_file="${cli_dir}/distribution/haifa-coding.yaml"

if [ "$#" -eq 0 ]; then
    if [ -z "${HOME:-}" ]; then
        printf '%s\n' "HOME is required for the default distribution directory." >&2
        exit 1
    fi
    output_dir="${HOME}/.haifa-agent/coding"
else
    output_dir=$1
fi

case "$output_dir" in
    /*) ;;
    *) output_dir="${repo_dir}/${output_dir}" ;;
esac

case "$output_dir" in
    /)
        printf '%s\n' "Refusing to use the filesystem root as the distribution directory." >&2
        exit 1
        ;;
esac

if [ "$#" -gt 1 ]; then
    printf '%s\n' "Usage: ./scripts/package-local-coding-agent.sh [output-directory]" >&2
    exit 1
fi

if [ ! -f "$launcher_file" ]; then
    printf '%s\n' "Coding Agent launcher is missing: ${launcher_file}" >&2
    exit 1
fi

if [ ! -f "$config_file" ]; then
    printf '%s\n' "Coding Agent default configuration is missing: ${config_file}" >&2
    exit 1
fi

printf '%s\n' "Building the local Coding Agent shaded JAR..."
(
    cd "$repo_dir"
    ./mvnw --batch-mode --no-transfer-progress \
        -pl :haifa-agent-cli -am clean package
)

jar_file=
for candidate in "${target_dir}"/haifa-agent-cli-*.jar; do
    [ -f "$candidate" ] || continue
    case "$(basename -- "$candidate")" in
        original-*|*-sources.jar|*-javadoc.jar) continue ;;
    esac
    if [ -n "$jar_file" ]; then
        printf '%s\n' "More than one shaded CLI JAR was found; run mvnw clean package first." >&2
        exit 1
    fi
    jar_file=$candidate
done

if [ -z "$jar_file" ]; then
    printf '%s\n' "The shaded CLI JAR was not produced in ${target_dir}." >&2
    exit 1
fi

if [ -e "$output_dir" ] && [ ! -d "$output_dir" ]; then
    printf '%s\n' "Distribution path exists and is not a directory: ${output_dir}" >&2
    exit 1
fi
mkdir -p "$output_dir"

cp "$jar_file" "${output_dir}/haifa-agent.jar"
cp "$launcher_file" "${output_dir}/haifa-coding"
cp "$config_file" "${output_dir}/haifa-coding.yaml"
chmod 755 "${output_dir}/haifa-coding"
chmod 644 "${output_dir}/haifa-agent.jar" "${output_dir}/haifa-coding.yaml"

printf '%s\n' ""
printf '%s\n' "Local Coding Agent distribution: ${output_dir}"
printf '%s\n' "Add it to PATH:"
printf '%s\n' "  export PATH=\"${output_dir}:\$PATH\""
printf '%s\n' "Set the model credential, enter any workspace, and launch:"
printf '%s\n' "  export DEEPSEEK_API_KEY='<secret>'"
printf '%s\n' "  cd /path/to/project"
printf '%s\n' "  haifa-coding"
