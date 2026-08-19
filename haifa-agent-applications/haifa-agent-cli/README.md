# Haifa Agent CLI

## Policy / Approval

CLI 保留 `ask / auto / deny` 兼容入口，并以 `LOW / MEDIUM / HIGH / NEVER` 风险阈值表达实际审批策略；SQLite 模式下 Policy Snapshot、Decision 和审批证据与 Runtime 使用同一权威数据库。CLI 不提供企业审批路由、待办或业务单据提交能力。

## Unified approval policy

`ask/auto/deny` 由产品 Policy Snapshot 表达，默认 `ask` 映射为 `LOW`，`auto` 映射为 `NEVER`，
`deny` 在 Catalog freeze 前移除 `execution.run` 及其 `execution.request_permissions` 配套入口。也可只配置
`approval.threshold` 为 `low`、`medium`、`high` 或 `never`；同时配置 mode 和 threshold 时必须使用兼容组合。
达到阈值的普通执行风险创建一次 Policy 审批，Tool Decision 沿调用链传给 Broker 复核，不产生第二个
控制台审批。`NEVER` 会自动执行包括 HIGH 在内的普通命令，但不覆盖可信分类器的硬拒绝、
Broker/Workspace/Sandbox 边界、Credential 重认证或一次性 Host 权限升级；后两者仍要求操作者交互。
权限申请只在普通执行使用与 Host 不同的隔离 Provider 时披露；默认 `host-guarded` 已经是受信 Host 路径，
不重复披露。

`haifa-agent-cli` 是 Coding Agent 的最高层生产装配与唯一可执行发行入口。它把同一个 Runtime、
Project、Workspace、Policy、Tool、Execution、Persistence 与 `CodingSessionService` 交给 tui4j
Terminal，同时保留兼容的 `-m` one-shot 模式。`haifa-agent-coding-terminal` 只负责 UI，不是第二个
可执行胖 JAR。

非 CLI 宿主和产品语义测试使用公开装配入口 `StandaloneCodingAgents.factory()`，按公共
`CodingAgentClientFactory` 契约创建标准客户端；
返回的 `StandaloneCodingAgent` 暴露 `CodingSessionClient`、`ProjectId` 和安全的装配元数据，并通过
`close()` 统一释放资源。需要为每次隔离运行注入不同 SQLite/Transcript 路径时，可使用接收显式环境
Map 的重载；调用方不得把 Secret 或完整 YAML 序列化进测试 Case。

生产 Coding Agent 使用 Coding 产品模块中的版本化短 Prompt；CLI 不再维护逐 Case 累积的长方法论
字符串。基础 Prompt 要求读取适用仓库指令和契约、做最小完整修改、按风险验证并检查最终 Diff。
Tool 专属协议由冻结 Tool Definition 披露，复杂计划与结果复核方法通过基础 Skill 按需加载。

长任务期间，Coding 产品从权威记录重建派生工作阶段并向模型注入有界脱敏投影；Terminal 只消费安全
阶段事件。`execution.run` 对 INSPECT、DIFF、TEST/BUILD 和其他命令分别应用输出预算，超大 Diff 返回
观察统计、截断标记和可选 Artifact Ref。模型返回空终态时，Runtime 只在同一冻结 Binding 上默认重试
两次，不切换 Provider/Model，也不会从空响应调度 Tool。

## 构建与运行

### 本地发行目录（macOS / Linux）

`scripts/package-local-coding-agent.sh` 会构建唯一的 shaded CLI JAR，并把可搬运的本地制品放进
同一个目录：

```text
haifa-coding-agent/
  haifa-coding          # 可加入 PATH 的 POSIX 启动脚本
  haifa-agent.jar       # 包含全部运行依赖的 shaded JAR
  haifa-coding.yaml     # 无密钥的安全默认配置
  data/
    runtime.db          # 首次运行后创建；SQLite 权威存储
    transcripts/        # JSONL 审计投影
```

默认发布到用户目录 `~/.haifa-agent/coding/`：

```bash
./scripts/package-local-coding-agent.sh
export PATH="$HOME/.haifa-agent/coding:$PATH"
export DEEPSEEK_API_KEY="<secret>"

cd /path/to/any/project
haifa-coding

# 打开选择器、最近 Session 或指定 Session
haifa-coding resume
haifa-coding resume --last
haifa-coding resume <SESSION_ID>
haifa-coding resume <SESSION_ID> "继续修复测试"
haifa-coding resume --last "继续前面的工作"
```

也可以将其他绝对路径作为第一个参数，覆盖默认发布目录。

把 `PATH` 配置写入 `~/.zshrc` 或 `~/.bashrc` 后可长期使用。`haifa-coding` 不切换目录，且 Java
入口未收到 `--workspace` 时默认使用进程当前目录，所以从哪个项目目录发起，该目录就是 Workspace。
发行配置只使用 `env://...` 引用，不包含密钥，默认保持
`approval=ask`、`host-guarded + network allow + shell auto`，并启用
`SQLITE_WITH_JSONL + protection=NONE`。SQLite 是 Session、Run、Tool Journal、Policy 证据等恢复状态
的唯一事实源；本地默认 payload 在磁盘上可读，不提供保密性，但仍执行格式、binding 和 digest 校验。
JSONL 只用于审计投影，不参与恢复。启动器按自身目录设置绝对数据路径，因此发行目录整体移动后仍可
使用；重新打包只覆盖 JAR、配置和启动器，不删除既有 `data/`。可通过
`haifa-coding --config /absolute/path/to/config.yaml` 使用自定义配置，也可显式传
`--workspace /absolute/path/to/project`；调用方参数位于默认参数之后，因此优先级更高。

需要让 `execution.run` 读取 JDK、SDK 或其他 Workspace 外工具链目录时，应将其物理绝对路径作为
只读 `execution.extraPathPolicies` 写入可信的自定义配置。不要把 API Key 写进 YAML。Java 21
必须能从 `JAVA_HOME/bin/java` 或 `PATH` 找到。

该发行入口用于日常本地项目。仓库根目录的 `scripts/run-haifa-coding-terminal.command` 继续保留，
作为会创建隔离 Fixture、Trace、SQLite 和人工验收证据的 macOS 测试入口；两者用途不同。

### 本地发行目录（Windows）

Windows PowerShell 使用 `scripts/package-local-coding-agent.ps1`，生成 shaded JAR、安全默认配置和
`haifa-coding.cmd`：

```text
coding\
  haifa-coding.cmd
  haifa-agent.jar
  haifa-coding.yaml
  data\
    runtime.db
    transcripts\
```

默认输出到当前用户的 `%USERPROFILE%\.haifa-agent\coding`，也可以指定绝对路径或相对仓库根目录的
路径：

```powershell
.\scripts\package-local-coding-agent.ps1
.\scripts\package-local-coding-agent.ps1 D:\tools\haifa-coding-agent
$env:Path = 'D:\tools\haifa-coding-agent;' + $env:Path

$env:DEEPSEEK_API_KEY = '<secret>'
Set-Location D:\path\to\any\project
haifa-coding
```

启动器优先使用 `%JAVA_HOME%\bin\java.exe`，否则从 `PATH` 查找 `java.exe`；必须使用 Java 21。
启动器不切换当前目录，调用方参数位于默认 `--config` 参数之后，因此仍可覆盖配置或显式指定
Workspace。默认 `protection=NONE` 不需要 continuation key；如改为 `AES_GCM`，则必须通过
`protectorRef: env://HAIFA_CONTINUATION_KEY` 注入跨重启稳定的 Base64 32 字节 AES key。启动器会创建
`data/transcripts` 并按发行目录设置 SQLite/JSONL 绝对路径，同时允许 `HAIFA_SQLITE_DATABASE_PATH` 和
`HAIFA_TRANSCRIPT_ROOT` 显式覆盖。打包入口使用 `-DskipTests`；它只生成发行制品，不替代
模块测试或 CI 验证。macOS/Linux 与 Windows 入口复用同一个 Python 3 打包核心；可通过
`HAIFA_PYTHON_EXECUTABLE` 固定解释器路径。

### macOS 全工具人工测试入口

仓库根目录的 `scripts/run-haifa-coding-terminal.command` 是可版本控制的 macOS 人工测试启动器。
它默认启用 CLI 当前全部可执行的内置文件/命令工具、两个基础 Skill 工具、命令网络和
`approval=auto`。本地 Utility MCP 健康检查失败时，脚本会从
`HAIFA_UTILITY_MCP_SERVICE_DIR` 指定的源码目录后台启动服务；服务可达后导入 Coding Agent
已审核的 Utility 工具。检测到 `ALIYUN_IQS_API_KEY` 或 `HAIFA_ALIYUN_IQS_KEY_FILE` 后还会启用
`web.search` 与 `web.fetch`。

```bash
./scripts/run-haifa-coding-terminal.command --check
./scripts/run-haifa-coding-terminal.command --build
./scripts/run-haifa-coding-terminal.command --approval=ask
```

启动器会禁用退出后离线验收命令的交互分页器；`/quit` 恢复 TUI 进入前的主屏后，验收摘要直接续写，
不会进入空白的 `less (END)` 页面。用户级 Git pager 配置不会改变这一行为。

`AUTO` 只减少普通 Workspace 写入、命令和网络读取的逐次确认，不绕过 Workspace、Sandbox、
Credential、Tool allowlist、审计或其他 fail-closed 门禁。可用
`HAIFA_AGENT_REPO_DIR`、`HAIFA_JAVA_HOME`、`HAIFA_DEEPSEEK_KEY_FILE`、
`HAIFA_ALIYUN_IQS_KEY_FILE`、`HAIFA_CONTINUATION_KEY_FILE`、`HAIFA_TEST_RUNS_ROOT`、
`HAIFA_UTILITY_MCP_URL` 和 `HAIFA_UTILITY_MCP_SERVICE_DIR` 覆盖本机路径与端点。

启动器会先把 `JAVA_HOME` 解析为物理目录，再冻结为 Local Native 只读额外路径。SDKMAN 等版本管理器
提供的 `current` 符号链接不会直接进入 Sandbox Profile；目标目录仍须存在、可执行并通过 Java 21
检查。

```powershell
.\mvnw.cmd -pl :haifa-agent-cli -am package
$jar = ".\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar"

# 帮助：不会初始化模型、Runtime、SQLite 或 tui4j Terminal
java -jar $jar --help

# 无 -m 时默认进入 Terminal；显式 --terminal 完全等价
java -jar $jar --terminal --workspace D:\haifa-agent-config\workspaces\terminal-manual `
  --config D:\haifa-agent-config\haifa-coding-terminal.yaml

# 兼容 one-shot
java -jar $jar --workspace D:\haifa-agent-config\workspaces\terminal-manual `
  --config D:\haifa-agent-config\haifa-coding-terminal.yaml `
  -m "分析当前项目并修复一个小问题"

# Session Resume；全局配置参数可以放在 resume 前面
java -jar $jar --workspace D:\haifa-agent-config\workspaces\terminal-manual `
  --config D:\haifa-agent-config\haifa-coding-terminal.yaml `
  resume --last "继续前面的工作"
```

构建后也可以将 `bin` 目录加入 `PATH`，使用 `haifa-cli.ps1` 启动。

`--terminal` 与 `-m/--message` 不能同时使用。非交互、`dumb` 或不支持的终端会快速返回稳定的
`TUI_UNAVAILABLE`。同一规范化且非符号链接的 Workspace 会生成带版本 namespace 的稳定
Project/Workspace 身份；绝对路径不进入 Prompt、Client Event、JSONL 或普通错误输出。

顶层 `resume` 仅恢复当前 Workspace 和调用方范围内的 Coding Session，不接管既有活动 Run。
`resume` 打开现有选择器，`resume --last` 使用产品层稳定排序选择最近 Session，指定 ID 时重新执行
产品授权。可选 Prompt 只在 Session 对账后为空闲状态时提交；若存在活动 Run，则保留 Prompt 草稿并
显示 `RUN_TAKEOVER_NOT_SUPPORTED`。`--model` 与 `resume` 的组合在 P0 中拒绝，避免静默覆盖 Session
下一 Run 的模型偏好。

## 真实联网编程配置

Terminal 不是离线演示壳：普通消息进入真实 `CodingSessionService` 与 AgentLoop，文件修改、Git、
`execution.run`、MCP、`web.search`/`web.fetch` 都走现有 Tool Pipeline、Policy、Approval 和
ExecutionBroker。下面是 Windows 上“明确可信测试 Workspace”的联网配置要点：

CLI 的 Coding Execution 装配默认请求 private required Scratch：`TMPDIR/TMP/TEMP/GOTMPDIR`
指向本次执行根，`GOCACHE` 指向其 `go-build` 子目录。Local Native Control Directory 或显式
Host Guarded Scratch Root 负责物理路径、权限和清理；配置、Prompt、Trace 与普通错误不披露该路径。
无法安全创建 Scratch 时命令不会启动。

```yaml
models:
  default: deepseek-responses-flash
  providers:
    - id: deepseek
      displayName: DeepSeek
      nativeStreaming: true
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      apiBindings:
        - style: openai-responses
          dialect: deepseek-openai-responses
        - style: anthropic-messages
          dialect: deepseek-anthropic-messages
          endpoint: https://api.deepseek.com/anthropic
      models:
        - id: deepseek-responses-flash
          displayName: DeepSeek Responses Flash
          providerModelId: deepseek-v4-flash
          style: openai-responses
          capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
        - id: deepseek-anthropic-flash
          displayName: DeepSeek Anthropic Messages Flash
          providerModelId: deepseek-v4-flash
          style: anthropic-messages
          capabilities: [TEXT_CHAT, TOOL_CALLING, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
tools:
  enabled: [file.list, file.stat, file.read, file.create, file.write, execution.run, web.search, web.fetch]
web:
  search:
    enabled: true
    provider: aliyun
    credentialRef: env://ALIYUN_IQS_API_KEY
  fetch:
    enabled: true
    provider: aliyun
    credentialRef: env://ALIYUN_IQS_API_KEY
approval:
  mode: ask
  threshold: low
execution:
  provider: host-guarded
  network: allow
  shell: powershell
persistence:
  mode: SQLITE
  databasePath: D:\haifa-agent-config\data\coding-terminal.db
  protectorRef: env://HAIFA_CONTINUATION_KEY
```

同一 Provider 可声明多个 Style Binding；Binding 省略 dialect 时使用 `standard`：

```yaml
models:
  default: deepseek-responses-flash
  providers:
    - id: deepseek
      displayName: DeepSeek
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      nativeStreaming: true
      apiBindings:
        - style: openai-chat-completions
          dialect: deepseek-openai-chat
        - style: openai-responses
          dialect: deepseek-openai-responses
      models:
        - id: deepseek-chat-pro
          displayName: DeepSeek Chat Pro
          providerModelId: deepseek-v4-pro
          style: openai-chat-completions
          capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
        - id: deepseek-responses-flash
          displayName: DeepSeek Responses Flash
          providerModelId: deepseek-v4-flash
          style: openai-responses
          capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
    - id: local-openai
      displayName: Local OpenAI Responses Gateway
      endpoint: ${OPENAI_BASE_URL:http://127.0.0.1:30000/v1}
      credentialRef: env://OPENAI_API_KEY
      nativeStreaming: true
      apiBindings:
        - style: openai-responses
      models:
        - id: local-openai-responses
          displayName: Local OpenAI Responses
          providerModelId: ${OPENAI_MODEL_ID:gpt-5.6-luna}
          style: openai-responses
          capabilities: [TEXT_CHAT]
          contextWindow: 131072
          maxOutputTokens: 8192
```

旧 `model`、`dialectId`、versioned Binding 配置不再接受。`--model`/`HAIFA_MODEL_ID` 只能选择已注册的内部 ID，不能临时
注入 Endpoint 或 Credential；未知 ID 会 fail closed。

Provider 是一级接入实例：Endpoint、Credential、百炼 Workspace/Region 只配置一次；其 `models`
是该 Provider 可用的模型列表。模型 `id` 是产品内全局唯一选择 ID，`providerModelId` 是供应商实际
模型或部署名称。Provider 持有共享 Endpoint、CredentialRef 与 `nativeStreaming`；Binding 只持有
`style`、可选 dialect 和可选完整 Endpoint 覆盖。DeepSeek Anthropic Messages 因 Base URL 不同，在
Binding 上覆盖 `https://api.deepseek.com/anthropic`。Coding Agent 不根据 Provider ID 推断协议；严格兼容
现有 Style 的新 Provider 省略 dialect，只增加配置。

`host-guarded + allow` 以当前 Windows 用户身份执行，允许普通宿主网络，也不能提供容器级文件隔离；
只应对自己检查并信任的测试 Workspace 使用。模型与 Web Provider 调用可能计费。密钥只通过
`env://...` 注入，不写入配置；`HAIFA_CONTINUATION_KEY` 必须是跨重启稳定的 Base64 32 字节 AES key。

ConPTY 离线验收可在 CLI 子进程中显式设置 `HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL=true`，此开关
只允许 `http://localhost`、`http://127.0.0.1` 或 IPv6 loopback Endpoint，不能放宽外部 HTTP
Provider。普通运行不应设置该变量。

发行配置中的本地 Responses Provider 只读取 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL_ID`，
并仅在显式设置 `HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL=true` 时允许 HTTP loopback。其当前能力只有
`TEXT_CHAT`，不会进入要求 `TOOL_CALLING` 的 Coding 模型列表；默认仍使用
`deepseek-responses-flash`。

## 安全 Trace

CLI 可实时订阅现有 `RuntimeTraceEvent`，不需要启用 `--verbose`：

最终失败输出使用 `[AgentErrorCode] 安全默认文案`，下一行显示可选 Diagnostic ID；非 Trace
输出不包含 Java 异常、Provider 原文或 Stack Trace。启用 SQLite 的 CLI 会把该 ID 对应的有界结构化
诊断写入数据库同级的 `diagnostics/<Diagnostic ID>.json`；文件仅包含错误码、Run/Attempt 标识、异常
类型和有界 Stack Frame，不保存异常消息、Prompt、Tool arguments、Provider 原文或完整宿主路径。

```powershell
$jar = ".\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar"

# 只显示关键模型、Tool、MCP 与 Skill 生命周期
java -jar $jar --config D:\haifa-agent-config\haifa-skill-live.yaml `
  --workspace D:\haifa-agent-config\workspaces\ascii-art `
  -m "使用 ascii-art Skill 创作一只帆船" `
  --trace summary

# 将所有安全 Runtime 事件写成 JSON Lines
java -jar $jar --config D:\haifa-agent-config\haifa-skill-live.yaml `
  --workspace D:\haifa-agent-config\workspaces\ascii-art `
  -m "使用 ascii-art Skill 创作一只帆船" `
  --trace jsonl `
  --trace-file D:\haifa-agent-config\ascii-art.trace.jsonl
```

- `--trace summary`：只输出模型完成/失败、Context 强制重建以及 Tool、MCP、Skill 开始/完成事件；
- `--trace detail`：输出每个 Runtime Trace envelope 和全部安全属性；
- `--trace jsonl`：每个安全事件输出为一行独立 JSON，适合脚本、日志采集和 E2E 证据；
- `--trace-file <path>`：将所选格式写入文件，必须与 `--trace` 一起使用；父目录必须已存在，目标不能是目录或符号链接，已有普通文件会被覆盖；
- one-shot 模式未指定 `--trace-file` 时 Trace 写入 stderr，模型的流式回答继续写入 stdout；
- Terminal 模式禁止 Trace 直接写入全屏 UI；需要诊断时必须同时指定 `--trace-file`，Trace 只进入该文件。

三个模式都只消费 Runtime 的 `safeAttributes`，并在 CLI 边界再次移除敏感键、ANSI/控制字符，限制字符串、集合和嵌套深度。不会输出 Prompt、Tool 原始参数/完整结果、Credential、reasoning 原文或供应商原始响应。`summary` 根据冻结 Tool Provider 区分普通 Tool、`mcp.<serverId>` MCP Tool 和 `haifa-runtime-skill` Skill Tool；它不会建立第二套调用链。

## 配置

配置优先级为命令行参数、环境变量、工作区 `.haifa-agent/config.yaml`、用户目录 `~/.haifa-agent/config.yaml` 和内置默认值。密钥只允许通过凭据引用提供：

```powershell
$env:DEEPSEEK_API_KEY = "<secret>"
```

```yaml
models:
  default: deepseek-responses-flash
  providers:
    - id: deepseek
      displayName: DeepSeek
      nativeStreaming: true
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      apiBindings:
        - style: openai-responses
          dialect: deepseek-openai-responses
      models:
        - id: deepseek-responses-flash
          displayName: DeepSeek Responses Flash
          providerModelId: deepseek-v4-flash
          style: openai-responses
          capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
tools:
  enabled: [file.list, file.stat, file.read, file.create, file.write, file.delete, file.move, execution.run]
skills:
  allowed: [task-planning, result-verification, my-test-skill]
  localDirectories:
    - id: personal
      root: D:\haifa-agent-config\skills
      priority: 100
      parserMode: strict
      origin: created
web:
  search:
    enabled: false
    provider: aliyun
    credentialRef: env://ALIYUN_IQS_API_KEY
  fetch:
    enabled: false
    provider: aliyun
    credentialRef: env://ALIYUN_IQS_API_KEY
mcp:
  servers:
    - id: utility
      displayName: Haifa Utility MCP
      endpoint: http://127.0.0.1:8091/mcp
      allowLoopbackHttp: true
      allowedTools: [time_now, calculate]
      aliasNamespace: utility
      policyProfile: utility
approval:
  mode: ask
  threshold: low
execution:
  provider: host-guarded
  network: allow
  shell: auto
  defaultTimeoutMillis: 120000
  maxTimeoutMillis: 1800000
  maxOutputLines: 2000
  maxOutputBytes: 51200
  maxProcesses: 8
  # "*" inherits ordinary host variables after secret-like names are removed.
  # An explicit list remains supported for stricter deployments.
  inheritEnvironment: ["*"]
  extraPathPolicies: []
runtime:
  maxIterations: 50
  maxToolCalls: 32
  maxWallTimeMillis: 300000
persistence:
  mode: MEMORY
```

`persistence.mode` 只允许 `MEMORY`、`SQLITE` 和 `SQLITE_WITH_JSONL`；CLI 内置配置默认
`MEMORY`，由打包脚本生成的 Haifa Coding Agent 发行配置默认 `SQLITE_WITH_JSONL`。持久模式示例：

```yaml
persistence:
  mode: SQLITE_WITH_JSONL
  databasePath: D:\haifa-agent-data\runtime.db
  transcriptRoot: D:\haifa-agent-data\transcripts
  protection: NONE
  busyTimeoutMillis: 5000
  maximumPayloadBytes: 1048576
```

数据库与 transcript 路径必须是绝对路径，父目录/Transcript 目录必须预先受控创建。`SQLITE` 不会创建
JSONL；JSONL 从不参与恢复。`protection` 支持 `NONE` 和 `AES_GCM`。`NONE` 将受控 payload 以带版本、
binding digest 和内容 digest 的明文格式写入 SQLite，只适用于可信本地目录。`AES_GCM` 必须同时配置
`protectorRef: env://HAIFA_CONTINUATION_KEY`；变量值必须是 Base64 编码的 32 字节 AES key，并在重启间
保持稳定。旧配置若包含 `protectorRef` 但省略 `protection`，继续按 `AES_GCM` 读取。对应环境变量覆盖为
`HAIFA_PERSISTENCE_MODE`、`HAIFA_PERSISTENCE_PROTECTION`、
`HAIFA_SQLITE_DATABASE_PATH`、`HAIFA_TRANSCRIPT_ROOT` 和
`HAIFA_CONTINUATION_PROTECTOR_REF`。

`tools.enabled` 使用内部点号名称；CLI 向模型披露时会映射为 `file_list`、`file_read`、`file_patch`、
`execution_run`、`request_permissions` 等 Provider-safe function name。`execution.run` 接收完整命令文本、Workspace 相对工作
目录和 timeout；任何本机已安装且可由配置 Shell 解析的非交互 CLI 都走同一生产路径，文档中的具体
命令仅是非穷举示例。Coding Agent 默认使用该通用 OS CLI 路径完成仓库级文件发现、内容搜索、源码
检查、构建和测试：文件发现优先 `rg --files`，内容搜索优先 `rg`，命令不存在时由模型按当前 Shell
选择替代方案。产品代码不识别搜索意图，也不拼接 `rg`、`grep` 或其他命令的具体选项。

Java `file.search` 仍是 Project Tool Catalog 支持的有界兼容能力，可在自定义 `tools.enabled` 中显式
加入；发行配置和 `CliConfiguration.defaults()` 不再默认披露它，避免 Coding Agent 在大型仓库反复走
逐文件 Java 扫描。通用 Shell 命令仍遵循配置的 Approval、ExecutionBroker、Workspace、Sandbox、输出
预算和审计边界；不会因为 `operationFamily=INSPECT` 是模型声明就自动降低授权要求。

`file.read` 1.2 默认只读取最多 64 KiB/400 行，并返回 `hasMore`、`nextCursor`、总字节数和文件版本。
后续窗口通过 `SeekableByteChannel` 从游标字节位置读取，不按文件大小分配内存；游标绑定逻辑路径和版本，
文件变化会返回 `FILE_CURSOR_STALE` 与 `RESTART_READ_FROM_CURRENT_VERSION`，只允许从当前版本无游标
确定性重读一次；跨路径复用仍作为无效游标拒绝。敏感路径返回 `USER_ACTION_REQUIRED`，明确要求用户
调整边界或授权，不建议模型通过随机改名、移动或复制绕过。`file.write` 遇到不存在目标返回
`USE_FILE_CREATE`，`file.create` 遇到已有目标返回 `USE_FILE_WRITE_OR_PATCH`，二者都不是原样重试信号。

`file.patch` 1.1 默认启用，接受一份 `*** Begin Patch` / `*** End Patch` 上下文补丁，可在同一次调用中
新增、删除、更新或移动多个文件。更新使用 `@@` 精确上下文定位；本地 Provider 以流方式读取源文件，
写入同目录临时文件，提交前再次校验内容哈希，再以原子替换（文件系统不支持时明确降级并记录）提交。
因此普通源码编辑不需要全量读取或重写大文件；`file.write` 仅用于有意整体替换的小文件。多文件调用按
顺序提交，失败结果返回已经提交的精确前缀，不宣称跨文件事务原子性。

`execution.provider` 只接受 `local-native` 或 `host-guarded`，`execution.network` 只接受 `deny`
或 `allow`。macOS、Linux、Windows 缺省值统一为 `host-guarded + allow + shell auto`，面向用户已经
检查并信任的本地 Workspace；命令输出保留进程产生的真实可用路径，并可在同一命令生命周期内启动、
访问和清理临时 loopback Server。普通宿主网络能力可用，因此不能把该默认值描述成外部网络隔离。

macOS/Linux 可显式配置 `local-native + deny`，分别由 Seatbelt/bubblewrap Adapter 在启动期预检并
兑现文件、网络和子进程边界。Windows 对 Local Native 返回 `SANDBOX_ADAPTER_UNAVAILABLE`，当前
不提供或伪装成同等级严格模式。

Local Native 的安全摘要会显示 Adapter、Workspace 模式、网络策略、无 Credential 注入，以及
CPU/内存/Kernel 未强制隔离。Host Guarded 以当前 OS 用户身份运行，不能阻止 Workspace 外文件、
普通网络或系统资源访问，Approval 也不等于隔离，因此不适合陌生仓库无人值守执行。长期 Server、
后台任务和 PTY 当前均不作为产品入口支持。
`extraPathPolicies` 只来自本地可信配置，包含稳定 `id`、绝对 `path` 和 `readOnly`；路径不会进入
模型 Schema。敏感目录、代理/Socket/Credential 环境、Host + DENY、未知 Provider/网络模式和
无法兑现的 Adapter 配置都在进程启动前 fail closed。

CLI Coding Profile 显式允许 `task-planning` 与 `result-verification` 两个 SDK 基础 Skill，并把
`skill_load`、`skill_resource_read` 注册到同一个 Runtime Tool Pipeline。模型开始时只看到 Skill
名称、描述和摘要；调用 `skill_load` 后，精确冻结版本的指令才进入后续上下文。资源必须再通过
`skill_resource_read` 按需读取。基础 Skill 不含外部 Tool、网络、Credential 或脚本依赖，运行时也不执行
Skill 包中的脚本。

`skills.localDirectories` 是 CLI 可信控制面配置，不接受模型或 Run 参数提供目录。每个 `root` 必须是
已存在、可读、非符号链接的绝对目录，也可以用完整的 `${ENV_NAME}` 占位符从进程环境注入该绝对路径。
Source 会有界递归穿过分类目录，并把首个包含 `SKILL.md`
的目录视为包根，不再进入该包的资源子目录；例如可发现
`D:\haifa-agent-config\skills\creative\ascii-art\SKILL.md`。当前 CLI 把这些来源绑定为本地用户的
`USER` Scope；`origin` 只允许 `created` 或 `imported`，`parserMode` 只允许 `strict` 或
`compatible`。目录中被发现的 Skill 还必须显式列入 `skills.allowed`，否则不会进入 Run 冻结、
模型摘要或激活范围。`USER` Scope 优先于 SDK Scope；同 Scope、同 priority 的同名冲突会使启动
fail closed。Source root 不进入 Prompt、Tool 参数或 Runtime 配置快照，也不得与 CLI Workspace
互相包含，否则普通文件 Tool 可能绕过 Skill 门禁，CLI 会在连接外部 MCP 或调用模型前拒绝启动。
可使用 `D:\haifa-agent-config` 作为测试配置根，把 Skill 放在 `skills\`，实际 Workspace 放在
同级的 `workspaces\<case>\`。

Web Tool 默认关闭。启用 Search 时需同时把 `web.search` 加入 `tools.enabled` 并设置
`web.search.enabled: true`；可选 Provider 为 `aliyun`、`brave`、`tavily`。启用 Fetch 时同理加入
`web.fetch`，可选 Provider 为 `aliyun`、`browserless`、`tavily`。Browserless 默认使用
`https://production-sfo.browserless.io/content` 与 `env://BROWSERLESS_TOKEN`；例如：

```yaml
tools:
  enabled: [file.read, web.fetch]
web:
  fetch:
    enabled: true
    provider: browserless
    credentialRef: env://BROWSERLESS_TOKEN
```

Browserless Token 只通过 Authorization 请求头发送，不要把 `?token=...` 写进 Endpoint。Provider 不读取环境变量；CLI 根据
`env://` 引用在启动期把密钥写入进程内加密 Credential Store，Runtime 在实际 Tool 调用期签发短期
`CredentialLease`。网络与凭据 Tool 默认仍按 `approval.mode` 进入审批策略，不会自动切换 Provider
或在失败时返回示例内容。

Tavily Search 与 Fetch 可分别选择，也可同时使用 `env://TAVILY_API_KEY`。Fetch 默认调用
`https://api.tavily.com/extract` 并返回 Markdown 或纯文本；Provider 仍为两个 Tool 建立独立、精确的
Credential Binding。

CLI 的 DeepSeek 和百炼冻结配置均强制关闭 thinking，并通过 Runtime output listener 实时打印安全的 answer delta；
reasoning 原文不会进入终端。使用 `--verbose` 时只会打印供应商报告的 reasoning token 计数，不记录或展示
reasoning 内容。

百炼 Provider 配置必须提供 `workspaceId`，`region` 缺省为 `cn-beijing`。CLI 不接受任意百炼主机，
而是固定推导 `https://{workspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1`。Provider、
Credential 和模型列表必须通过 `models.providers` 显式配置；`--model` 或 `HAIFA_MODEL_ID` 只负责
从已配置列表中选择模型。

`mcp.servers` 在 CLI 启动时连接并发现远端工具。每个 Server 必须使用稳定的小写 `id`、显式 `allowedTools` 和唯一 `aliasNamespace`；示例工具向模型披露为 `utility_time_now`、`utility_calculate`。发现不到、Schema 不兼容或不在本地审核策略中的配置工具会使启动失败，不会静默降级。

`policyProfile: conservative` 可用于任意显式 allowlist，但默认按高风险、未知幂等性和始终审批处理。`policyProfile: utility` 只接受 `CodingAgentMcpProfile` 已审核的 Utility 子集。生产 Server 必须使用 HTTPS；`allowLoopbackHttp: true` 只允许 `127.0.0.1` 或 `localhost` 开发端点。当前 CLI MCP 装配只支持无认证 Streamable HTTP，Credential 注入和 stdio 尚未开放为 CLI 配置。

风险达到配置阈值的 Shell 命令要求控制台确认；默认 `ask/LOW` 因而审批所有普通执行。Shell 审批显示完整 command、逻辑 workdir、timeout、Shell 类型及 Host 非强隔离提示。CLI 接受指向当前 Workspace 本身或其子目录的绝对 `workdir`，并在受信装配边界将其规范化为逻辑相对路径；Workspace 外绝对路径仍拒绝。网络或系统 `git` / `gh` 登录环境被 Sandbox 隔离时，模型只能用失败结果中的 `toolCallId` 请求对同一条直接、非破坏性的系统 `git` / `gh` 命令做一次 `HOST_NETWORK_ACCESS` 重试；不能修改命令意图、生成权限或批准自己的请求。`--approval auto` 映射为 `NEVER`，会自动执行可信分类为 LOW/MEDIUM/HIGH 的普通命令，包括 `git push`、`gh pr create` 和复合 Shell 命令；它只适用于用户明确信任的本地工作区，并仍经过 Broker、Workspace capability、Profile、环境和审计。可信分类硬拒绝、一次性 Host 权限升级和 Credential 重认证不会因 `auto` 自动批准。`--approval deny` 会在 Catalog freeze 前移除 `execution.run` 与 `execution.request_permissions`，模型不可见，底层授权仍 fail closed。

系统 Git/GH 只做基础风险分级，不提供命令专用 Wrapper。Tool Result 保留原始退出码，并单独投影命令语义：
`git diff --exit-code` / `--no-index` 的退出 1 是 `EXPECTED_VARIANT/DIFFERENCES_FOUND`，`git grep` 的退出 1
是 `EMPTY_RESULT/NO_MATCHES`；无效 revision、构建或测试的非零退出仍是失败。复合命令风险提升返回
`COMMAND_RISK_ESCALATED`，未知 Git 子命令返回 `GIT_COMMAND_UNKNOWN_HIGH_RISK`，不可信
`operationFamily` 返回 `OPERATION_HINT_IGNORED` 或 `UNVERIFIED`。认证环境覆盖硬拒绝使用
`AUTHENTICATION_OVERRIDE_DENIED`，受限网络失败使用 `NETWORK_PERMISSION_REQUIRED`，二者分别引导移除
覆盖或通过托管的一次性权限请求处理，而不是重复执行原命令。

`execution.shell` 支持 `auto`、`bash` 和 `powershell`。自定义 Shell 必须通过本地配置中的绝对 `shellPath` 提供，不能来自 Tool 参数。环境配置只保存允许继承的名称；Host Guarded 统一由公共解析器提供真实 OS 用户 HOME 与三端最小命令环境，Local Native 输入不携带宿主 HOME/AppData/XDG/TMP。两种模式都拒绝 API Key、`*_TOKEN`、`*_SECRET`、云凭据、代理凭据，以及 `PYTHONHOME`、`PYTHONPATH`、`PYTHONUSERBASE`、`VIRTUAL_ENV`、`CONDA_PREFIX`、`NODE_PATH` 等解释器边界变量。命令输出实时脱敏展示，最终模型结果默认限制为首尾合计 2000 行且最多 50KB，中段带明确省略标记；较大分通道输出通过 Output Ref 访问。探索性 `INSPECT` 达到预算后会停止进程树并要求收窄查询，其他命令继续排空到进程结束。CLI timeout 与 Ctrl+C 会发送 Runtime CANCEL，并有界等待 Broker 收敛进程树。

CLI 在冻结 Definition 时把可信配置解析后的 Shell 显示名加入模型指令，要求 `execution_run` 只生成该
Shell 支持的命令语法，避免在 Windows PowerShell 中混入 POSIX 命令；Shell 的实际路径、审批、能力与
Sandbox 约束仍由可信装配和 Broker 决定，模型不能覆盖。该环境指令同时说明 `PATH` 中任意非交互 CLI
均可使用，并要求模型在命令缺失时探测和切换替代方案、收窄过宽查询、保持输出有界。

CLI 使用 Execution Core 公共增量 Observer，不再在产品内维护扫描算法，也不再为每条 OS 命令执行前后各生成一次全量 Workspace Manifest。启动后的首次执行建立一次基线，
后续通过 `WatchService` 收集执行窗口内的候选路径，只重新检查和哈希候选文件；事件溢出、Watcher
失效或候选状态无法确认时才在授权 Workspace 内执行受限重同步，不能确认则以
`WORKSPACE_CHANGE_OBSERVER_RESYNC_FAILED` fail closed。
冻结的 ignore policy 排除标准构建/IDE 目录，并读取根
`.gitignore` 中不含 glob 的目录规则。默认还忽略 `.pytest_cache`、`.mypy_cache`、`.ruff_cache`、`.tox`、
`.venv` 和 `__pycache__`；`!` 只撤销可能包含该重新纳入目录的正向目录规则，不再清空其他无关规则。
进程启动前 Observer 不可用时使用稳定错误
`WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE` 且不标记 DISPATCHED；只有 OS 进程创建成功后才进入 DISPATCHED。
进程启动后的增量对账失败仍按结果不确定失败关闭。

当前已包含 tui4j Terminal、顶层 `resume` 五种形式、最近 100 条安全可见历史、真实 `/resume` 搜索、
Session 重命名/归档/逻辑删除、线性历史
`/compact`、根 `AGENTS.md` 冻结与 `/reload`、受治理的 `!`/`!!`、安全 `/export`、Steer、持久
Follow-up、Cancel、Approval selector 和 SQLite Session/Queue/Cursor 恢复。尚未包含 PTY、后台守护
进程、Session Tree/Fork/Clone、模型登录或 Workflow Graph。模型切换只覆盖可信静态目录内的空闲
Session，不包含动态发现或自动 fallback。Host Provider 不是容器或虚拟机，
不能阻止当前 OS 用户本来可访问的 Workspace 外文件、网络或系统资源。

## 真实模型 Coding E2E

统一 E2E 测试模块包含 9 个真实 DeepSeek/百炼模型驱动的 CLI 编程 E2E，覆盖单文件修复、多文件功能、
回归测试、Maven 配置、等价重构、文件迁移、脏工作区保护、失败恢复和审批拒绝。用例清单及初始工程位于
`haifa-agent-testing/haifa-agent-e2e-tests/src/test/resources/coding-e2e/`，每次执行都复制到新的隔离
Workspace；产品 CLI 模块不再承载跨产品 E2E Harness，也不会回放 Stub 或历史模型响应。

普通 `test` 和 `ci-fast` 不会访问真实模型。Live 批次必须显式提供以下环境：

```text
HAIFA_CLI_LIVE_E2E_TEST=true
HAIFA_FT_ENABLED=true
HAIFA_FT_MODE=LIVE
HAIFA_FT_RUN_ID=<unique-batch-id>
HAIFA_FT_ROOT=<new-empty-absolute-directory>
DEEPSEEK_API_KEY=<secret-manager-injected-value>
```

Live E2E 默认直接使用三端统一的 `host-guarded + allow + shell auto`，不再要求 Windows 专属覆盖。
只有为兼容外部编排而显式重复声明可信 Host 时，才可同时设置：

```text
HAIFA_CLI_LIVE_E2E_EXECUTION_PROVIDER=host-guarded
HAIFA_CLI_LIVE_E2E_EXECUTION_NETWORK=allow
```

两个变量必须成对出现且只能是上述值；缺少一项或声明其他组合都会 fail closed。Host Guarded 不能
提供容器级文件、网络或系统资源隔离。macOS/Linux Local Native 严格验证由独立 Gate 负责，不与
默认 Coding Live E2E 混算；Windows 没有 Local Native Adapter。

百炼批次将最后一项替换为：

```text
HAIFA_CLI_LIVE_E2E_PROVIDER=aliyun-bailian
HAIFA_BAILIAN_WORKSPACE_ID=<required-workspace-id>
HAIFA_BAILIAN_REGION=cn-beijing
HAIFA_BAILIAN_MODEL_ID=qwen-plus
DASHSCOPE_API_KEY=<secret-manager-injected-value>
```

`HAIFA_FT_ROOT` 必须包含 `.haifa-cli-live-e2e-root`，内容与 `HAIFA_FT_RUN_ID` 完全相同，且除该
sentinel 外初始为空。测试不会永久删除批次目录；执行者应将其作为 CI Artifact 隔离并按 TTL 清理。

```bash
./mvnw -pl :haifa-agent-e2e-tests -am -Pci-integration -DskipITs=false \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=CodingAgentLiveE2E clean verify
```

每个通过的用例会在 `target/coding-agent-live-e2e-evidence/` 生成脱敏 JSON，包含模型调用 ID、
Provider/模型/Adapter 版本、Usage、Tool Call 统计、耗时、Fixture digest、修改逻辑路径和 Oracle 结果；
不包含 API Key、任务全文、模型原始响应、reasoning 原文或真实 Workspace 路径。测试题不依赖 Web
Search，除真实模型 Endpoint 外不需要外部信息服务。

当前硬门禁是 Run 正常完成、独立 Oracle 通过、受保护文件和批次边界未变化、真实模型证据完整且无敏感
信息泄漏。耗时、Token、模型调用数、工具调用数和失败工具结果作为二级趋势指标记录，用于后续比较模型与
Prompt 效率；现阶段除“失败后恢复”专用用例外，不因单次效率波动判失败。
