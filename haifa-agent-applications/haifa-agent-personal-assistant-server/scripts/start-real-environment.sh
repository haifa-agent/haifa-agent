#!/usr/bin/env bash

set -euo pipefail

FRONTEND_PORT=20000
BACKEND_PORT=20001
MCP_PORT=20002

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIRECTORY/../../.." && pwd -P)"
SERVER_DIRECTORY="$REPOSITORY_ROOT/haifa-agent-applications/haifa-agent-personal-assistant-server"
WEB_DIRECTORY="$REPOSITORY_ROOT/haifa-agent-applications/haifa-agent-personal-assistant-web"
RUNTIME_DIRECTORY="$REPOSITORY_ROOT/local-tmp/personal-assistant-real"
DATA_DIRECTORY="$RUNTIME_DIRECTORY/data"
LOG_DIRECTORY="$RUNTIME_DIRECTORY/logs"
STATE_FILE="$RUNTIME_DIRECTORY/last-start.json"
STOP_STATE_FILE="$RUNTIME_DIRECTORY/last-stop.json"
MAVEN_WRAPPER="$REPOSITORY_ROOT/mvnw"

DEEPSEEK_KEY_FILE="${HAIFA_DEEPSEEK_KEY_FILE:-$HOME/workspace/ss-deepseek.txt}"
ALIYUN_IQS_KEY_FILE="${HAIFA_ALIYUN_IQS_KEY_FILE:-$HOME/workspace/ss-aliyun-iqs.txt}"
CONTINUATION_KEY_FILE="${HAIFA_PERSONAL_CONTINUATION_KEY_FILE:-$HOME/workspace/ss-haifa-personal-continuation.txt}"
UTILITY_MCP_DIRECTORY="${HAIFA_UTILITY_MCP_DIRECTORY:-$HOME/workspace/haifa/haifa-ai/haifa-ai-utility-mcp-server}"
PERSONAL_SKILL_ROOT="${HAIFA_PERSONAL_SKILL_ROOT:-$HOME/agents/hermes-agent/optional-skills/finance}"
TRUSTED_SCRIPT_MANIFEST="${HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST:-}"
STARTUP_TIMEOUT_SECONDS=180
REBUILD=false
STOP=false
DRY_RUN=false

ALLOWED_MCP_TOOLS="location_search,weather_current,weather_forecast,air_quality,time_now,time_convert,currency_rate,currency_convert,holiday_list,holiday_next,workday_is_workday,workday_add,calculate,unit_convert,wikipedia_search,wikipedia_summary,microsoft_docs_search,microsoft_docs_fetch,microsoft_code_sample_search"

usage() {
    cat <<'EOF'
Usage:
  start-real-environment.sh [options]
  start-real-environment.sh --stop [--dry-run]

Options:
  --deepseek-key-file PATH         File containing only the DeepSeek API key.
  --aliyun-iqs-key-file PATH       File containing only the Aliyun IQS API key.
  --continuation-key-file PATH     Persistent Base64-encoded 32-byte continuation key.
  --utility-mcp-directory PATH     Utility MCP Server Maven project directory.
  --personal-skill-root PATH       Skill source whose immediate child packages contain SKILL.md.
  --trusted-script-manifest PATH   Optional trusted script manifest.
  --startup-timeout-seconds N      Per-service startup timeout, from 30 to 600 (default: 180).
  --rebuild                        Rebuild backend and frontend; all three ports must be free.
  --stop                           Stop the environment recorded in last-start.json.
  --dry-run                        With --stop, validate and print targets without stopping them.
  -h, --help                       Show this help.

The same paths may be supplied through:
  HAIFA_DEEPSEEK_KEY_FILE
  HAIFA_ALIYUN_IQS_KEY_FILE
  HAIFA_PERSONAL_CONTINUATION_KEY_FILE
  HAIFA_UTILITY_MCP_DIRECTORY
  HAIFA_PERSONAL_SKILL_ROOT
  HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST
EOF
}

die() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

require_option_value() {
    local option_name="$1"
    local remaining_count="$2"
    if [[ "$remaining_count" -lt 2 ]]; then
        die "$option_name requires a value."
    fi
}

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --deepseek-key-file)
            require_option_value "$1" "$#"
            DEEPSEEK_KEY_FILE="$2"
            shift 2
            ;;
        --aliyun-iqs-key-file)
            require_option_value "$1" "$#"
            ALIYUN_IQS_KEY_FILE="$2"
            shift 2
            ;;
        --continuation-key-file)
            require_option_value "$1" "$#"
            CONTINUATION_KEY_FILE="$2"
            shift 2
            ;;
        --utility-mcp-directory)
            require_option_value "$1" "$#"
            UTILITY_MCP_DIRECTORY="$2"
            shift 2
            ;;
        --personal-skill-root)
            require_option_value "$1" "$#"
            PERSONAL_SKILL_ROOT="$2"
            shift 2
            ;;
        --trusted-script-manifest)
            require_option_value "$1" "$#"
            TRUSTED_SCRIPT_MANIFEST="$2"
            shift 2
            ;;
        --startup-timeout-seconds)
            require_option_value "$1" "$#"
            STARTUP_TIMEOUT_SECONDS="$2"
            shift 2
            ;;
        --rebuild)
            REBUILD=true
            shift
            ;;
        --stop)
            STOP=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Unknown option: $1"
            ;;
    esac
done

if ! [[ "$STARTUP_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] ||
        [[ "$STARTUP_TIMEOUT_SECONDS" -lt 30 ]] ||
        [[ "$STARTUP_TIMEOUT_SECONDS" -gt 600 ]]; then
    die "--startup-timeout-seconds must be an integer from 30 to 600."
fi
if [[ "$STOP" == true && "$REBUILD" == true ]]; then
    die "--stop and --rebuild cannot be used together."
fi
if [[ "$DRY_RUN" == true && "$STOP" != true ]]; then
    die "--dry-run may only be used with --stop."
fi

required_command() {
    local name="$1"
    command -v "$name" 2>/dev/null || die "Required command '$name' was not found on PATH."
}

http_endpoint_is_healthy() {
    local uri="$1"
    curl --fail --silent --show-error --max-time 3 --output /dev/null "$uri" 2>/dev/null
}

listening_process_ids() {
    local port="$1"
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | sort -u || true
}

listening_process_id() {
    local port="$1"
    local output
    local count

    output="$(listening_process_ids "$port")"
    if [[ -z "$output" ]]; then
        return 1
    fi
    count="$(printf '%s\n' "$output" | wc -l | tr -d '[:space:]')"
    if [[ "$count" -ne 1 ]]; then
        die "Port $port has multiple listening processes; refusing to guess an owner."
    fi
    printf '%s\n' "$output"
}

local_port_is_listening() {
    local port="$1"
    [[ -n "$(listening_process_ids "$port")" ]]
}

wait_for_http_endpoint() {
    local name="$1"
    local uri="$2"
    local process_id="$3"
    local timeout_seconds="$4"
    local standard_output_log="$5"
    local standard_error_log="$6"
    local deadline=$((SECONDS + timeout_seconds))

    while [[ "$SECONDS" -lt "$deadline" ]]; do
        if ! kill -0 "$process_id" 2>/dev/null; then
            local exit_code=0
            wait "$process_id" || exit_code=$?
            die "$name exited with code $exit_code. Logs: $standard_output_log ; $standard_error_log"
        fi
        if http_endpoint_is_healthy "$uri"; then
            return
        fi
        sleep 0.5
    done
    die "$name did not become healthy within $timeout_seconds seconds. Logs: $standard_output_log ; $standard_error_log"
}

wait_for_port_release() {
    local port="$1"
    local timeout_seconds="${2:-30}"
    local deadline=$((SECONDS + timeout_seconds))

    while [[ "$SECONDS" -lt "$deadline" ]]; do
        if ! local_port_is_listening "$port"; then
            return
        fi
        sleep 0.25
    done
    die "Port $port was not released within $timeout_seconds seconds."
}

absolute_existing_path() {
    local path="$1"
    local parent
    local name

    if [[ -d "$path" ]]; then
        (cd "$path" && pwd -P)
        return
    fi
    parent="$(dirname "$path")"
    name="$(basename "$path")"
    [[ -d "$parent" ]] || return 1
    printf '%s/%s\n' "$(cd "$parent" && pwd -P)" "$name"
}

read_secret_file() {
    local path="$1"
    local label="$2"
    local value

    [[ -f "$path" ]] || die "$label key file was not found: $path"
    value="$(tr -d '\r\n' < "$path")"
    [[ -n "$value" ]] || die "$label key file is empty: $path"
    printf '%s' "$value"
}

create_continuation_key_file() {
    local path="$1"
    local parent

    parent="$(dirname "$path")"
    if [[ ! -d "$parent" ]]; then
        mkdir -p "$parent"
        chmod 700 "$parent"
    fi
    umask 077
    openssl rand -base64 32 | tr -d '\n' > "$path"
    chmod 600 "$path"
}

validate_continuation_key() {
    local value="$1"
    local path="$2"
    local decoded_file
    local decoded_length

    decoded_file="$(mktemp "${TMPDIR:-/tmp}/haifa-continuation.XXXXXX")"
    if ! printf '%s' "$value" | openssl base64 -d -A > "$decoded_file" 2>/dev/null; then
        rm -f "$decoded_file"
        die "Continuation key file does not contain valid Base64: $path"
    fi
    decoded_length="$(wc -c < "$decoded_file" | tr -d '[:space:]')"
    rm -f "$decoded_file"
    [[ "$decoded_length" -eq 32 ]] ||
        die "Continuation key must decode to exactly 32 bytes: $path"
}

validate_process_identity() {
    local role="$1"
    local process_id="$2"
    local expected_process_name="$3"
    local expected_command_line_token="$4"
    local process_command
    local process_name

    kill -0 "$process_id" 2>/dev/null ||
        die "$role PID $process_id no longer exists. No process was stopped."
    process_command="$(ps -p "$process_id" -o command= 2>/dev/null || true)"
    process_name="$(ps -p "$process_id" -o comm= 2>/dev/null || true)"
    process_name="${process_name##*/}"
    [[ "$process_name" == "$expected_process_name" ]] ||
        die "$role PID $process_id is '$process_name', expected '$expected_process_name'. No process was stopped."
    [[ -n "$process_command" && "$process_command" == *"$expected_command_line_token"* ]] ||
        die "$role PID $process_id command line does not contain '$expected_command_line_token'. No process was stopped."
}

NODE_COMMAND="$(required_command node)"
LSOF_COMMAND="$(required_command lsof)"
CURL_COMMAND="$(required_command curl)"

# Keep resolved command paths visible to shellcheck and diagnostics.
: "$LSOF_COMMAND" "$CURL_COMMAND"

write_start_state() {
    local output_path="$1"
    shift
    printf '%s\n' "$@" | "$NODE_COMMAND" -e '
const fs = require("node:fs");
const output = process.argv[1];
const records = fs.readFileSync(0, "utf8").split(/\n/).filter(Boolean).map((line) => {
  const [Role, Status, Pid, Url, WorkDirectory, Stdout, Stderr] = line.split("\t");
  return {
    Role,
    Status,
    Pid: Pid ? Number(Pid) : null,
    Url,
    WorkDirectory,
    Stdout: Stdout || null,
    Stderr: Stderr || null
  };
});
const temporary = `${output}.tmp-${process.pid}`;
fs.writeFileSync(temporary, `${JSON.stringify(records, null, 2)}\n`, { mode: 0o600 });
fs.renameSync(temporary, output);
' "$output_path"
}

write_stop_state() {
    local output_path="$1"
    shift
    printf '%s\n' "$@" | "$NODE_COMMAND" -e '
const fs = require("node:fs");
const output = process.argv[1];
const records = fs.readFileSync(0, "utf8").split(/\n/).filter(Boolean).map((line) => {
  const [Role, Status, Pid, Port] = line.split("\t");
  return { Role, Status, Pid: Pid ? Number(Pid) : null, Port: Number(Port) };
});
const temporary = `${output}.tmp-${process.pid}`;
fs.writeFileSync(temporary, `${JSON.stringify(records, null, 2)}\n`, { mode: 0o600 });
fs.renameSync(temporary, output);
' "$output_path"
}

if [[ "$STOP" == true ]]; then
    [[ -f "$STATE_FILE" ]] ||
        die "Startup state file was not found: $STATE_FILE. No process was stopped."

    state_roles=()
    state_process_ids=()
    while IFS=$'\t' read -r role process_id; do
        state_roles[${#state_roles[@]}]="$role"
        state_process_ids[${#state_process_ids[@]}]="$process_id"
    done < <("$NODE_COMMAND" -e '
const records = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
for (const record of records) {
  process.stdout.write(`${record.Role || ""}\t${record.Pid || ""}\n`);
}
' "$STATE_FILE")

    definition_roles=("personal-web" "personal-backend" "utility-mcp")
    definition_ports=("$FRONTEND_PORT" "$BACKEND_PORT" "$MCP_PORT")
    definition_process_names=("node" "java" "java")
    definition_tokens=(
        "$WEB_DIRECTORY"
        "$SERVER_DIRECTORY"
        "org.wrj.haifa.ai.utilitymcp.UtilityMcpServerApplication"
    )
    target_roles=()
    target_ports=()
    target_process_ids=()
    stop_records=()

    for ((definition_index = 0; definition_index < ${#definition_roles[@]}; definition_index++)); do
        role="${definition_roles[$definition_index]}"
        port="${definition_ports[$definition_index]}"
        recorded_process_id=""
        for ((state_index = 0; state_index < ${#state_roles[@]}; state_index++)); do
            if [[ "${state_roles[$state_index]}" == "$role" ]]; then
                recorded_process_id="${state_process_ids[$state_index]}"
                break
            fi
        done

        current_process_id="$(listening_process_id "$port" || true)"
        if [[ -z "$current_process_id" ]]; then
            stop_records[${#stop_records[@]}]="$role"$'\t'"already-stopped"$'\t\t'"$port"
            continue
        fi
        [[ -n "$recorded_process_id" ]] ||
            die "No recorded PID exists for $role, but port $port is listening. No process was stopped."
        [[ "$recorded_process_id" == "$current_process_id" ]] ||
            die "$role port $port belongs to PID $current_process_id, but state records PID $recorded_process_id. No process was stopped."

        validate_process_identity \
            "$role" \
            "$current_process_id" \
            "${definition_process_names[$definition_index]}" \
            "${definition_tokens[$definition_index]}"
        target_roles[${#target_roles[@]}]="$role"
        target_ports[${#target_ports[@]}]="$port"
        target_process_ids[${#target_process_ids[@]}]="$current_process_id"
    done

    for ((target_index = 0; target_index < ${#target_roles[@]}; target_index++)); do
        role="${target_roles[$target_index]}"
        port="${target_ports[$target_index]}"
        process_id="${target_process_ids[$target_index]}"
        if [[ "$DRY_RUN" == true ]]; then
            printf 'Would stop %s PID %s on port %s.\n' "$role" "$process_id" "$port"
            stop_records[${#stop_records[@]}]="$role"$'\t'"validated"$'\t'"$process_id"$'\t'"$port"
        else
            kill -TERM "$process_id"
            wait_for_port_release "$port"
            stop_records[${#stop_records[@]}]="$role"$'\t'"stopped"$'\t'"$process_id"$'\t'"$port"
        fi
    done

    printf '\nPersonal Assistant stop validation completed.\n'
    for record in "${stop_records[@]}"; do
        role="${record%%$'\t'*}"
        remaining_record="${record#*$'\t'}"
        status="${remaining_record%%$'\t'*}"
        remaining_record="${remaining_record#*$'\t'}"
        process_id="${remaining_record%%$'\t'*}"
        port="${remaining_record##*$'\t'}"
        printf '  %-18s %-16s PID=%-8s port=%s\n' "$role" "$status" "${process_id:--}" "$port"
    done
    if [[ "$DRY_RUN" == true ]]; then
        printf 'Dry run was enabled; no process was stopped.\n'
    else
        mkdir -p "$RUNTIME_DIRECTORY"
        chmod 700 "$RUNTIME_DIRECTORY"
        write_stop_state "$STOP_STATE_FILE" "${stop_records[@]}"
        printf 'Stop state: %s\n' "$STOP_STATE_FILE"
    fi
    exit 0
fi

JAVA_COMMAND="$(required_command java)"
NPM_COMMAND="$(required_command npm)"
MAVEN_COMMAND="$(required_command mvn)"
OPENSSL_COMMAND="$(required_command openssl)"

: "$OPENSSL_COMMAND"

[[ -x "$MAVEN_WRAPPER" ]] || die "Executable Maven wrapper was not found: $MAVEN_WRAPPER"
[[ -d "$PERSONAL_SKILL_ROOT" ]] || die "Personal Skill root was not found: $PERSONAL_SKILL_ROOT"
[[ -d "$UTILITY_MCP_DIRECTORY" ]] || die "Utility MCP directory was not found: $UTILITY_MCP_DIRECTORY"
[[ -f "$UTILITY_MCP_DIRECTORY/pom.xml" ]] ||
    die "Utility MCP pom.xml was not found under: $UTILITY_MCP_DIRECTORY"

skill_package_count=0
for skill_directory in "$PERSONAL_SKILL_ROOT"/*/; do
    if [[ -f "${skill_directory}SKILL.md" ]]; then
        skill_package_count=$((skill_package_count + 1))
    fi
done
[[ "$skill_package_count" -gt 0 ]] ||
    die "Personal Skill root contains no immediate child with SKILL.md: $PERSONAL_SKILL_ROOT"

DEEPSEEK_API_KEY_VALUE="$(read_secret_file "$DEEPSEEK_KEY_FILE" "DeepSeek")"
ALIYUN_IQS_API_KEY_VALUE="$(read_secret_file "$ALIYUN_IQS_KEY_FILE" "Aliyun IQS")"
PERSONAL_SKILL_ROOT="$(absolute_existing_path "$PERSONAL_SKILL_ROOT")"
UTILITY_MCP_DIRECTORY="$(absolute_existing_path "$UTILITY_MCP_DIRECTORY")"

TRUSTED_SCRIPT_MANIFEST_PATH=""
if [[ -n "$TRUSTED_SCRIPT_MANIFEST" ]]; then
    [[ -f "$TRUSTED_SCRIPT_MANIFEST" ]] ||
        die "Trusted script manifest is not a file: $TRUSTED_SCRIPT_MANIFEST"
    TRUSTED_SCRIPT_MANIFEST_PATH="$(absolute_existing_path "$TRUSTED_SCRIPT_MANIFEST")"
fi

if [[ ! -f "$CONTINUATION_KEY_FILE" ]]; then
    create_continuation_key_file "$CONTINUATION_KEY_FILE"
    printf 'Created a persistent continuation key file: %s\n' "$CONTINUATION_KEY_FILE"
fi
CONTINUATION_KEY_VALUE="$(tr -d '\r\n' < "$CONTINUATION_KEY_FILE")"
validate_continuation_key "$CONTINUATION_KEY_VALUE" "$CONTINUATION_KEY_FILE"

mkdir -p "$DATA_DIRECTORY" "$LOG_DIRECTORY"
chmod 700 "$RUNTIME_DIRECTORY" "$DATA_DIRECTORY" "$LOG_DIRECTORY"

if [[ "$REBUILD" == true ]] &&
        { local_port_is_listening "$FRONTEND_PORT" ||
          local_port_is_listening "$BACKEND_PORT" ||
          local_port_is_listening "$MCP_PORT"; }; then
    die "Rebuild requires ports 20000, 20001, and 20002 to be free. Stop the existing environment first."
fi

find_server_jar() {
    local target_directory="$SERVER_DIRECTORY/target"
    local selected=""
    local selected_timestamp=0
    local candidate
    local candidate_timestamp

    for candidate in "$target_directory"/haifa-agent-personal-assistant-server-*.jar; do
        [[ -f "$candidate" ]] || continue
        case "$candidate" in
            *-sources.jar|*-javadoc.jar) continue ;;
        esac
        candidate_timestamp="$(stat -f '%m' "$candidate")"
        if [[ "$candidate_timestamp" -gt "$selected_timestamp" ]]; then
            selected="$candidate"
            selected_timestamp="$candidate_timestamp"
        fi
    done
    printf '%s' "$selected"
}

SERVER_JAR="$(find_server_jar)"
if [[ "$REBUILD" == true || -z "$SERVER_JAR" ]]; then
    printf 'Building the Personal Assistant backend...\n'
    "$MAVEN_WRAPPER" -pl :haifa-agent-personal-assistant-server -am -DskipTests package
    SERVER_JAR="$(find_server_jar)"
    [[ -n "$SERVER_JAR" ]] || die "Backend build completed without producing an executable server JAR."
fi

SERVE_SCRIPT="$WEB_DIRECTORY/node_modules/serve/build/main.js"
if [[ ! -f "$SERVE_SCRIPT" ]]; then
    printf 'Installing locked frontend dependencies...\n'
    (
        cd "$WEB_DIRECTORY"
        "$NPM_COMMAND" ci
    )
fi

FRONTEND_INDEX="$WEB_DIRECTORY/dist/index.html"
if [[ "$REBUILD" == true || ! -f "$FRONTEND_INDEX" ]]; then
    printf 'Building the standalone Personal Assistant frontend...\n'
    (
        export VITE_PERSONAL_ASSISTANT_API_BASE_URL="http://127.0.0.1:$BACKEND_PORT/api/v1"
        cd "$WEB_DIRECTORY"
        "$NPM_COMMAND" run build
    )
fi

TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
service_records=()

MCP_HEALTH_URI="http://127.0.0.1:$MCP_PORT/actuator/health"
if http_endpoint_is_healthy "$MCP_HEALTH_URI"; then
    MCP_PROCESS_ID="$(listening_process_id "$MCP_PORT")"
    service_records[${#service_records[@]}]="utility-mcp"$'\t'"reused"$'\t'"$MCP_PROCESS_ID"$'\t'"$MCP_HEALTH_URI"$'\t'"$UTILITY_MCP_DIRECTORY"$'\t\t'
elif local_port_is_listening "$MCP_PORT"; then
    die "Port $MCP_PORT is occupied, but Utility MCP health check failed. No process was stopped."
else
    MCP_STDOUT="$LOG_DIRECTORY/utility-mcp-$TIMESTAMP.out.log"
    MCP_STDERR="$LOG_DIRECTORY/utility-mcp-$TIMESTAMP.err.log"
    (
        trap '' HUP
        export UTILITY_MCP_PORT="$MCP_PORT"
        cd "$UTILITY_MCP_DIRECTORY"
        exec "$MAVEN_COMMAND" spring-boot:run
    ) >"$MCP_STDOUT" 2>"$MCP_STDERR" &
    MCP_LAUNCHER_PROCESS_ID=$!
    wait_for_http_endpoint \
        "Utility MCP" \
        "$MCP_HEALTH_URI" \
        "$MCP_LAUNCHER_PROCESS_ID" \
        "$STARTUP_TIMEOUT_SECONDS" \
        "$MCP_STDOUT" \
        "$MCP_STDERR"
    MCP_PROCESS_ID="$(listening_process_id "$MCP_PORT")"
    service_records[${#service_records[@]}]="utility-mcp"$'\t'"started"$'\t'"$MCP_PROCESS_ID"$'\t'"$MCP_HEALTH_URI"$'\t'"$UTILITY_MCP_DIRECTORY"$'\t'"$MCP_STDOUT"$'\t'"$MCP_STDERR"
fi

BACKEND_HEALTH_URI="http://127.0.0.1:$BACKEND_PORT/actuator/health"
if http_endpoint_is_healthy "$BACKEND_HEALTH_URI"; then
    BACKEND_PROCESS_ID="$(listening_process_id "$BACKEND_PORT")"
    service_records[${#service_records[@]}]="personal-backend"$'\t'"reused"$'\t'"$BACKEND_PROCESS_ID"$'\t'"$BACKEND_HEALTH_URI"$'\t'"$SERVER_DIRECTORY"$'\t\t'
elif local_port_is_listening "$BACKEND_PORT"; then
    die "Port $BACKEND_PORT is occupied, but Personal Assistant health check failed. No process was stopped."
else
    BACKEND_STDOUT="$LOG_DIRECTORY/personal-backend-$TIMESTAMP.out.log"
    BACKEND_STDERR="$LOG_DIRECTORY/personal-backend-$TIMESTAMP.err.log"
    (
        trap '' HUP
        export DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY_VALUE"
        export ALIYUN_IQS_API_KEY="$ALIYUN_IQS_API_KEY_VALUE"
        export HAIFA_PERSONAL_CONTINUATION_KEY="$CONTINUATION_KEY_VALUE"
        export HAIFA_PERSONAL_DATA_DIR="$DATA_DIRECTORY"
        export HAIFA_PERSONAL_MODEL_MODE="remote"
        export HAIFA_PERSONAL_ALLOW_DETERMINISTIC="false"
        export HAIFA_PERSONAL_MODEL_ENDPOINT="https://api.deepseek.com"
        export HAIFA_PERSONAL_MODEL_ID="deepseek-v4-flash"
        export HAIFA_PERSONAL_MODEL_CREDENTIAL="env://DEEPSEEK_API_KEY"
        export HAIFA_PERSONAL_WEB_ENABLED="true"
        export HAIFA_PERSONAL_WEB_CREDENTIAL="env://ALIYUN_IQS_API_KEY"
        export HAIFA_PERSONAL_SKILL_ROOT="$PERSONAL_SKILL_ROOT"
        export HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST="$TRUSTED_SCRIPT_MANIFEST_PATH"
        export HAIFA_PERSONAL_MCP_MODE="external"
        export HAIFA_PERSONAL_MCP_ENDPOINT="http://127.0.0.1:$MCP_PORT/mcp"
        export HAIFA_PERSONAL_MCP_ALLOWED_TOOLS="$ALLOWED_MCP_TOOLS"
        export HAIFA_PERSONAL_MCP_ALIAS_NAMESPACE="utility"
        export HAIFA_PERSONAL_MCP_SERVER_ID="haifa-utility"
        export HAIFA_PERSONAL_MCP_DISPLAY_NAME="Haifa Utility MCP"
        export HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED="true"
        cd "$SERVER_DIRECTORY"
        exec "$JAVA_COMMAND" -jar "$SERVER_JAR"
    ) >"$BACKEND_STDOUT" 2>"$BACKEND_STDERR" &
    BACKEND_LAUNCHER_PROCESS_ID=$!
    wait_for_http_endpoint \
        "Personal Assistant backend" \
        "$BACKEND_HEALTH_URI" \
        "$BACKEND_LAUNCHER_PROCESS_ID" \
        "$STARTUP_TIMEOUT_SECONDS" \
        "$BACKEND_STDOUT" \
        "$BACKEND_STDERR"
    BACKEND_PROCESS_ID="$(listening_process_id "$BACKEND_PORT")"
    service_records[${#service_records[@]}]="personal-backend"$'\t'"started"$'\t'"$BACKEND_PROCESS_ID"$'\t'"$BACKEND_HEALTH_URI"$'\t'"$SERVER_DIRECTORY"$'\t'"$BACKEND_STDOUT"$'\t'"$BACKEND_STDERR"
fi

FRONTEND_URI="http://127.0.0.1:$FRONTEND_PORT/"
if http_endpoint_is_healthy "$FRONTEND_URI"; then
    FRONTEND_PROCESS_ID="$(listening_process_id "$FRONTEND_PORT")"
    service_records[${#service_records[@]}]="personal-web"$'\t'"reused"$'\t'"$FRONTEND_PROCESS_ID"$'\t'"$FRONTEND_URI"$'\t'"$WEB_DIRECTORY"$'\t\t'
elif local_port_is_listening "$FRONTEND_PORT"; then
    die "Port $FRONTEND_PORT is occupied, but the Personal Assistant frontend check failed. No process was stopped."
else
    FRONTEND_STDOUT="$LOG_DIRECTORY/personal-web-$TIMESTAMP.out.log"
    FRONTEND_STDERR="$LOG_DIRECTORY/personal-web-$TIMESTAMP.err.log"
    (
        trap '' HUP
        cd "$WEB_DIRECTORY"
        exec "$NODE_COMMAND" \
            "$SERVE_SCRIPT" \
            -s "$WEB_DIRECTORY/dist" \
            -l "tcp://127.0.0.1:$FRONTEND_PORT" \
            --no-clipboard
    ) >"$FRONTEND_STDOUT" 2>"$FRONTEND_STDERR" &
    FRONTEND_LAUNCHER_PROCESS_ID=$!
    wait_for_http_endpoint \
        "Personal Assistant frontend" \
        "$FRONTEND_URI" \
        "$FRONTEND_LAUNCHER_PROCESS_ID" \
        "$STARTUP_TIMEOUT_SECONDS" \
        "$FRONTEND_STDOUT" \
        "$FRONTEND_STDERR"
    FRONTEND_PROCESS_ID="$(listening_process_id "$FRONTEND_PORT")"
    service_records[${#service_records[@]}]="personal-web"$'\t'"started"$'\t'"$FRONTEND_PROCESS_ID"$'\t'"$FRONTEND_URI"$'\t'"$WEB_DIRECTORY"$'\t'"$FRONTEND_STDOUT"$'\t'"$FRONTEND_STDERR"
fi

write_start_state "$STATE_FILE" "${service_records[@]}"

printf '\nReal Personal Assistant environment is ready.\n'
for record in "${service_records[@]}"; do
    IFS=$'\t' read -r role status process_id url _ <<< "$record"
    printf '  %-18s %-8s PID=%-8s %s\n' "$role" "$status" "${process_id:--}" "$url"
done

printf '\nWork directories:\n'
printf '  Repository:       %s\n' "$REPOSITORY_ROOT"
printf '  Personal Web:     %s\n' "$WEB_DIRECTORY"
printf '  Personal Server:  %s\n' "$SERVER_DIRECTORY"
printf '  Utility MCP:      %s\n' "$UTILITY_MCP_DIRECTORY"
printf '  Personal Skills:  %s\n' "$PERSONAL_SKILL_ROOT"
if [[ -n "$TRUSTED_SCRIPT_MANIFEST_PATH" ]]; then
    printf '  Trust Manifest:   %s\n' "$TRUSTED_SCRIPT_MANIFEST_PATH"
fi
printf '  Runtime data:     %s\n' "$DATA_DIRECTORY"
printf '  Runtime logs:     %s\n' "$LOG_DIRECTORY"

printf '\nAccess addresses:\n'
printf '  Personal Web:     %s\n' "$FRONTEND_URI"
printf '  Personal API:     http://127.0.0.1:%s/api/v1\n' "$BACKEND_PORT"
printf '  Backend health:   %s\n' "$BACKEND_HEALTH_URI"
printf '  Backend OpenAPI:  http://127.0.0.1:%s/api/v1/openapi.json\n' "$BACKEND_PORT"
printf '  Utility MCP:      http://127.0.0.1:%s/mcp\n' "$MCP_PORT"
printf '  MCP health:       %s\n' "$MCP_HEALTH_URI"
printf '  Web Tools:        web.search, web.fetch (Aliyun IQS)\n'

printf '\nState: %s\n' "$STATE_FILE"
printf 'Logs:  %s\n' "$LOG_DIRECTORY"
printf 'Secrets were loaded into the backend child process environment only and were not printed.\n'
