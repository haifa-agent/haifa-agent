#!/bin/zsh

set -euo pipefail
umask 077

# The launcher owns a real TTY. Never let post-exit diagnostics replace the restored
# main screen with an interactive pager (for example an empty less screen ending in "(END)").
export GIT_PAGER=cat
export PAGER=cat
export LESS=-FRX

readonly SCRIPT_PATH="${0:A}"
readonly SCRIPT_DIR="${SCRIPT_PATH:h}"
readonly REPO_DIR="${HAIFA_AGENT_REPO_DIR:-${SCRIPT_DIR:h}}"
readonly JAR_FILE="${REPO_DIR}/haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.0-SNAPSHOT.jar"
readonly FIXTURE_DIR="${REPO_DIR}/haifa-agent-testing/haifa-agent-e2e-tests/src/test/resources/coding-e2e/fixtures/single-file-bugfix"
readonly VERIFIER_FILE="${REPO_DIR}/haifa-agent-testing/haifa-agent-e2e-tests/src/test/resources/coding-e2e/support/verify_java.py"
readonly DEEPSEEK_KEY_FILE="${HAIFA_DEEPSEEK_KEY_FILE:-${REPO_DIR:h}/ss共享密钥.txt}"
readonly ALIYUN_IQS_KEY_FILE="${HAIFA_ALIYUN_IQS_KEY_FILE:-${REPO_DIR:h}/ss-aliyun-iqs.txt}"
readonly CONTINUATION_KEY_FILE="${HAIFA_CONTINUATION_KEY_FILE:-${REPO_DIR:h}/haifa-continuation-key.txt}"
readonly TEST_RUNS_ROOT="${HAIFA_TEST_RUNS_ROOT:-${HOME}/haifa-agent-test-runs}"
readonly UTILITY_MCP_ENDPOINT="${HAIFA_UTILITY_MCP_URL:-http://127.0.0.1:8091/mcp}"
readonly UTILITY_MCP_HEALTH_URL="${HAIFA_UTILITY_MCP_HEALTH_URL:-http://127.0.0.1:8091/actuator/health}"
readonly UTILITY_MCP_SERVICE_DIR="${HAIFA_UTILITY_MCP_SERVICE_DIR:-${REPO_DIR:h}/haifa/haifa-ai/haifa-ai-utility-mcp-server}"
readonly SCRIPT_NAME="${0:t}"

force_build=false
check_only=false
mcp_requested=true
approval_mode=auto
mcp_started=false

usage() {
  print -r -- "用法: ${SCRIPT_NAME} [--build] [--check] [--no-mcp] [--approval=auto|ask|deny]"
  print -r -- ""
  print -r -- "  无参数            启动全工具、允许网络、低审批 Terminal"
  print -r -- "  --build           启动前强制重新构建 CLI"
  print -r -- "  --check           只检查依赖并显示将启用的能力"
  print -r -- "  --no-mcp          不连接本地 Utility MCP"
  print -r -- "  --approval=auto   普通写入与命令不逐次审批（默认）"
  print -r -- "  --approval=ask    写入、命令与网络工具逐次审批"
  print -r -- "  --approval=deny   禁用命令执行；其他策略仍 fail closed"
}

fail() {
  print -u2 -r -- "错误: $*"
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "缺少文件: $1"
}

cleanup_secrets() {
  unset DEEPSEEK_API_KEY HAIFA_CONTINUATION_KEY ALIYUN_IQS_API_KEY
}

trap cleanup_secrets EXIT HUP INT TERM

for argument in "$@"; do
  case "$argument" in
    --build)
      force_build=true
      ;;
    --check)
      check_only=true
      ;;
    --no-mcp)
      mcp_requested=false
      ;;
    --approval=auto)
      approval_mode=auto
      ;;
    --approval=ask)
      approval_mode=ask
      ;;
    --approval=deny)
      approval_mode=deny
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "未知参数: $argument"
      ;;
  esac
done

[[ "$(uname -s)" == "Darwin" ]] || fail "此脚本使用 macOS Local Native 安全配置，只能在 macOS 运行"
[[ -d "$REPO_DIR" ]] || fail "缺少主仓: $REPO_DIR"
require_file "${REPO_DIR}/mvnw"
[[ -d "$FIXTURE_DIR" ]] || fail "缺少测试 Fixture: $FIXTURE_DIR"
require_file "$VERIFIER_FILE"
require_file "$DEEPSEEK_KEY_FILE"
require_file "$CONTINUATION_KEY_FILE"
command -v git >/dev/null 2>&1 || fail "找不到 git"
command -v curl >/dev/null 2>&1 || fail "找不到 curl"

configured_java_home="${HAIFA_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$configured_java_home" ]] && [[ -x /usr/libexec/java_home ]]; then
  configured_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
[[ -n "$configured_java_home" ]] || fail "找不到 Java 21；请设置 HAIFA_JAVA_HOME"
configured_java_home="${configured_java_home:A}"
[[ -x "${configured_java_home}/bin/java" ]] || fail "Java 不可执行: ${configured_java_home}/bin/java"

export JAVA_HOME="$configured_java_home"
export PATH="${JAVA_HOME}/bin:${PATH}"

java_major="$("${JAVA_HOME}/bin/java" -version 2>&1 | sed -n '1{s/.*version "\([0-9][0-9]*\).*/\1/p;q;}')"
[[ "$java_major" == "21" ]] || fail "需要 Java 21，当前检测到: ${java_major:-未知}"

if [[ "$force_build" == true || ! -f "$JAR_FILE" ]]; then
  print -r -- "正在构建 Haifa Agent CLI..."
  (
    cd "$REPO_DIR"
    ./mvnw -pl :haifa-agent-cli -am package
  )
fi
require_file "$JAR_FILE"

if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  DEEPSEEK_API_KEY=""
  IFS= read -r DEEPSEEK_API_KEY < "$DEEPSEEK_KEY_FILE" || [[ -n "$DEEPSEEK_API_KEY" ]]
fi
[[ -n "$DEEPSEEK_API_KEY" ]] || fail "DeepSeek Key 不可用"
export DEEPSEEK_API_KEY

if [[ -z "${HAIFA_CONTINUATION_KEY:-}" ]]; then
  HAIFA_CONTINUATION_KEY=""
  IFS= read -r HAIFA_CONTINUATION_KEY < "$CONTINUATION_KEY_FILE" || [[ -n "$HAIFA_CONTINUATION_KEY" ]]
fi
[[ -n "$HAIFA_CONTINUATION_KEY" ]] || fail "Continuation Key 不可用"
export HAIFA_CONTINUATION_KEY

web_enabled=false
if [[ -z "${ALIYUN_IQS_API_KEY:-}" ]] && [[ -f "$ALIYUN_IQS_KEY_FILE" ]]; then
  ALIYUN_IQS_API_KEY=""
  IFS= read -r ALIYUN_IQS_API_KEY < "$ALIYUN_IQS_KEY_FILE" || [[ -n "$ALIYUN_IQS_API_KEY" ]]
fi
if [[ -n "${ALIYUN_IQS_API_KEY:-}" ]]; then
  export ALIYUN_IQS_API_KEY
  web_enabled=true
fi

mcp_enabled=false
if [[ "$mcp_requested" == true ]]; then
  mcp_health_code="$(curl -sS --max-time 2 -o /dev/null -w '%{http_code}' "$UTILITY_MCP_HEALTH_URL" || true)"
  if [[ "$mcp_health_code" != "200" ]] && [[ -d "$UTILITY_MCP_SERVICE_DIR" ]]; then
    command -v mvn >/dev/null 2>&1 || fail "Utility MCP 未运行且找不到 mvn，无法自动启动"
    mcp_repo_dir="${UTILITY_MCP_SERVICE_DIR:h:h}"
    mcp_log_dir="${TEST_RUNS_ROOT}/utility-mcp"
    mcp_log_file="${mcp_log_dir}/utility-mcp.log"
    mcp_pid_file="${mcp_log_dir}/utility-mcp.pid"
    mkdir -p "$mcp_log_dir"
    chmod 700 "$mcp_log_dir"
    print -r -- "Utility MCP 未运行，正在后台启动..."
    (
      cd "$mcp_repo_dir"
      nohup mvn -pl haifa-ai/haifa-ai-utility-mcp-server -am spring-boot:run \
        > "$mcp_log_file" 2>&1 < /dev/null &
      print -r -- "$!" > "$mcp_pid_file"
    )
    chmod 600 "$mcp_log_file" "$mcp_pid_file"
    for attempt in {1..45}; do
      mcp_health_code="$(curl -sS --max-time 2 -o /dev/null -w '%{http_code}' "$UTILITY_MCP_HEALTH_URL" || true)"
      [[ "$mcp_health_code" == "200" ]] && break
      sleep 1
    done
    if [[ "$mcp_health_code" != "200" ]]; then
      fail "Utility MCP 启动后未就绪，请检查日志: $mcp_log_file"
    fi
    mcp_started=true
  fi
  if [[ "$mcp_health_code" == "200" ]]; then
    mcp_enabled=true
  fi
fi

print_capabilities() {
  print -r -- ""
  print -r -- "Haifa Coding Agent Terminal 启动配置"
  print -r -- "审批模式: ${approval_mode:u}（AUTO 最少审批；凭据与安全门禁不会绕过）"
  print -r -- "命令网络: ALLOW"
  print -r -- "内置工具: file.list, file.stat, file.read, file.search, file.create, file.write,"
  if [[ "$approval_mode" == "deny" ]]; then
    print -r -- "          file.delete, file.move（DENY 模式会从模型目录移除 execution.run）"
  else
    print -r -- "          file.delete, file.move, execution.run"
  fi
  print -r -- "基础 Skill 工具: skill.load, skill.resource.read"
  if [[ "$mcp_enabled" == true ]]; then
    if [[ "$mcp_started" == true ]]; then
      print -r -- "Utility MCP: 已后台启动并启用 9 个已审核工具（${UTILITY_MCP_ENDPOINT}）"
    else
      print -r -- "Utility MCP: 已连接并启用 9 个已审核工具（${UTILITY_MCP_ENDPOINT}）"
    fi
  elif [[ "$mcp_requested" == true ]]; then
    print -r -- "Utility MCP: 服务不可达，跳过；启动服务后重新运行即可自动启用"
  else
    print -r -- "Utility MCP: 已通过 --no-mcp 禁用"
  fi
  if [[ "$web_enabled" == true ]]; then
    print -r -- "Web 工具: web.search, web.fetch（Aliyun IQS）"
  else
    print -r -- "Web 工具: 未启用（设置 ALIYUN_IQS_API_KEY 或创建 ${ALIYUN_IQS_KEY_FILE} 后自动启用）"
  fi
  print -r -- ""
}

print_capabilities
if [[ "$check_only" == true ]]; then
  exit 0
fi

case_id="terminal-manual-$(date -u +%Y%m%dT%H%M%SZ)-$$"
case_root="${TEST_RUNS_ROOT}/${case_id}"
workspace_dir="${case_root}/workspace"
trace_dir="${case_root}/traces"
data_dir="${case_root}/data"
transcript_dir="${case_root}/transcripts"
run_config="${case_root}/terminal.yaml"
trace_file="${trace_dir}/terminal-detail.log"

[[ ! -e "$case_root" ]] || fail "运行目录已存在: $case_root"
mkdir -p "$workspace_dir" "$trace_dir" "$data_dir" "$transcript_dir"
chmod 700 "$case_root" "$workspace_dir" "$trace_dir" "$data_dir" "$transcript_dir"
cp -R "${FIXTURE_DIR}/." "${workspace_dir}/"
cp "$VERIFIER_FILE" "${workspace_dir}/verify.py"

{
  print -r -- "models:"
  print -r -- "  default: deepseek-v4-pro"
  print -r -- "  providers:"
  print -r -- "    - id: deepseek"
  print -r -- "      displayName: DeepSeek"
  print -r -- "      endpoint: https://api.deepseek.com"
  print -r -- "      credentialRef: env://DEEPSEEK_API_KEY"
  print -r -- "      models:"
  print -r -- "        - id: deepseek-v4-pro"
  print -r -- "          displayName: DeepSeek V4 Pro"
  print -r -- "          providerModelId: deepseek-v4-pro"
  print -r -- ""
  print -r -- "tools:"
  print -r -- "  enabled:"
  print -r -- "    - file.list"
  print -r -- "    - file.stat"
  print -r -- "    - file.read"
  print -r -- "    - file.search"
  print -r -- "    - file.create"
  print -r -- "    - file.write"
  print -r -- "    - file.delete"
  print -r -- "    - file.move"
  print -r -- "    - execution.run"
  if [[ "$web_enabled" == true ]]; then
    print -r -- "    - web.search"
    print -r -- "    - web.fetch"
  fi
  print -r -- ""
  print -r -- "skills:"
  print -r -- "  allowed:"
  print -r -- "    - task-planning"
  print -r -- "    - result-verification"
  if [[ "$web_enabled" == true ]]; then
    print -r -- ""
    print -r -- "web:"
    print -r -- "  search:"
    print -r -- "    enabled: true"
    print -r -- "    provider: aliyun"
    print -r -- "    credentialRef: env://ALIYUN_IQS_API_KEY"
    print -r -- "  fetch:"
    print -r -- "    enabled: true"
    print -r -- "    provider: aliyun"
    print -r -- "    credentialRef: env://ALIYUN_IQS_API_KEY"
  fi
  if [[ "$mcp_enabled" == true ]]; then
    print -r -- ""
    print -r -- "mcp:"
    print -r -- "  servers:"
    print -r -- "    - id: utility"
    print -r -- "      displayName: Haifa Utility MCP"
    print -r -- "      endpoint: ${UTILITY_MCP_ENDPOINT}"
    print -r -- "      allowLoopbackHttp: true"
    print -r -- "      allowedTools:"
    print -r -- "        - calculate"
    print -r -- "        - time_convert"
    print -r -- "        - time_now"
    print -r -- "        - unit_convert"
    print -r -- "        - microsoft_code_sample_search"
    print -r -- "        - microsoft_docs_fetch"
    print -r -- "        - microsoft_docs_search"
    print -r -- "        - wikipedia_search"
    print -r -- "        - wikipedia_summary"
    print -r -- "      aliasNamespace: utility"
    print -r -- "      policyProfile: utility"
  fi
  print -r -- ""
  print -r -- "approval:"
  print -r -- "  mode: ${approval_mode}"
  print -r -- ""
  print -r -- "execution:"
  print -r -- "  provider: local-native"
  print -r -- "  network: allow"
  print -r -- "  shell: auto"
  print -r -- "  defaultTimeoutMillis: 120000"
  print -r -- "  maxTimeoutMillis: 600000"
  print -r -- "  maxOutputLines: 2000"
  print -r -- "  maxOutputBytes: 51200"
  print -r -- "  maxProcesses: 8"
  print -r -- "  inheritEnvironment:"
  print -r -- "    - PATH"
  print -r -- "    - HOME"
  print -r -- "    - TMP"
  print -r -- "    - TEMP"
  print -r -- "    - JAVA_HOME"
  print -r -- "    - MAVEN_OPTS"
  print -r -- "  extraPathPolicies:"
  print -r -- "    - id: java-21-home"
  print -r -- "      path: ${JAVA_HOME}"
  print -r -- "      readOnly: true"
  print -r -- ""
  print -r -- "runtime:"
  print -r -- "  maxIterations: 50"
  print -r -- "  maxToolCalls: 32"
  print -r -- "  maxWallTimeMillis: 600000"
  print -r -- ""
  print -r -- "persistence:"
  print -r -- "  mode: SQLITE_WITH_JSONL"
  print -r -- "  protectorRef: env://HAIFA_CONTINUATION_KEY"
  print -r -- "  busyTimeoutMillis: 5000"
  print -r -- "  maximumPayloadBytes: 1048576"
} > "$run_config"
chmod 600 "$run_config"

git -C "$workspace_dir" init -q
git -C "$workspace_dir" add .
git -C "$workspace_dir" \
  -c user.name="Haifa Terminal Tester" \
  -c user.email="terminal-test@local.invalid" \
  commit -qm "initial fixture"

export HAIFA_SQLITE_DATABASE_PATH="${data_dir}/runtime.db"
export HAIFA_TRANSCRIPT_ROOT="$transcript_dir"

task_text="只在当前 Workspace 工作。读取现有源码和测试，修复 Clamp 的边界缺陷：小于 minimum 返回 minimum，大于 maximum 返回 maximum，区间内保持原值。保持公开 API。修改后运行 sh verify.sh，验证成功后总结修改和测试结果。"

if command -v pbcopy >/dev/null 2>&1; then
  print -rn -- "$task_text" | pbcopy
  clipboard_note="测试任务已复制到剪贴板，进入界面后按 Command+V，再按 Enter。"
else
  clipboard_note="进入界面后复制下方测试任务，再按 Enter。"
fi

print -r -- "Haifa Coding Agent Terminal 人工测试"
print -r -- "运行目录: $case_root"
print -r -- "Workspace: $workspace_dir"
print -r -- "SQLite: $HAIFA_SQLITE_DATABASE_PATH"
print -r -- "Trace: $trace_file"
print -r -- ""
print -r -- "$clipboard_note"
print -r -- "测试任务："
print -r -- "$task_text"
print -r -- ""
if [[ "$approval_mode" == "ask" ]]; then
  print -r -- "审批框中先检查目标、命令和 Workdir，再选择 approve 或 reject。"
else
  print -r -- "当前为 ${approval_mode:u} 模式；高风险凭据与安全门禁仍可能阻止操作。"
fi
print -r -- ""

set +e
"${JAVA_HOME}/bin/java" -jar "$JAR_FILE" \
  --terminal \
  --workspace "$workspace_dir" \
  --config "$run_config" \
  --approval "$approval_mode" \
  --timeout PT10M \
  --trace detail \
  --trace-file "$trace_file" \
  --verbose
terminal_rc=$?
set -e

cleanup_secrets

print -r -- ""
print -r -- "Terminal 已退出（状态码: $terminal_rc），开始离线验收..."
git --no-pager -C "$workspace_dir" status --short

verification_rc=0
if ! git --no-pager -C "$workspace_dir" diff --check; then
  print -u2 -r -- "Git diff 检查失败"
  verification_rc=1
fi

if [[ -f "${workspace_dir}/verify.sh" ]]; then
  if (
    cd "$workspace_dir"
    sh verify.sh
  ); then
    print -r -- "verify.sh: PASS"
  else
    print -u2 -r -- "verify.sh: FAIL"
    verification_rc=1
  fi
else
  print -u2 -r -- "verify.sh: 缺失"
  verification_rc=1
fi

if [[ -f "$trace_file" ]]; then
  if command -v rg >/dev/null 2>&1; then
    trace_event_count="$(rg -c 'model\.invoke|tool\.execute|finishReason' "$trace_file" 2>/dev/null || true)"
  else
    trace_event_count="$(grep -Ec 'model\.invoke|tool\.execute|finishReason' "$trace_file" 2>/dev/null || true)"
  fi
  print -r -- "Trace 关键事件匹配数: ${trace_event_count:-0}"
fi

print -r -- "完整产物: $case_root"

if (( terminal_rc != 0 )); then
  exit "$terminal_rc"
fi
exit "$verification_rc"
