# Haifa Agent CLI

## Policy / Approval

CLI 的 `ask / auto / deny` 继续保持 Coding Agent 级的简单权限体验；SQLite 模式下 Policy Snapshot、Decision 和审批证据与 Runtime 使用同一权威数据库。CLI 不提供企业审批路由、待办或业务单据提交能力。

## Unified approval policy

`ask/auto/deny` 由产品 Policy Snapshot 表达，默认仍为 `ask`。`auto` 只自动满足允许自动化的
Capability Confirmation；Critical/Never 冲突、无目标网络、Credential 重认证和
Broker/Workspace/Sandbox 硬边界仍 fail closed。`execution.run` 的 Tool Decision 沿调用链传给
Broker 复核，不产生第二个控制台审批；`deny` 仍在 Catalog freeze 前移除该 Tool。

`haifa-agent-cli` 是 Coding Agent 的最高层生产装配与唯一可执行发行入口。它把同一个 Runtime、
Project、Workspace、Policy、Tool、Execution、Persistence 与 `CodingSessionService` 交给 tui4j
Terminal，同时保留兼容的 `-m` one-shot 模式。`haifa-agent-coding-terminal` 只负责 UI，不是第二个
可执行胖 JAR。

生产 Coding Agent 使用 Coding 产品模块中的版本化短 Prompt；CLI 不再维护逐 Case 累积的长方法论
字符串。基础 Prompt 要求读取适用仓库指令和契约、做最小完整修改、按风险验证并检查最终 Diff。
Tool 专属协议由冻结 Tool Definition 披露，复杂计划与结果复核方法通过基础 Skill 按需加载。

## 构建与运行

### 本地发行目录（macOS / Linux）

`scripts/package-local-coding-agent.sh` 会构建唯一的 shaded CLI JAR，并把可搬运的本地制品放进
同一个目录：

```text
haifa-coding-agent/
  haifa-coding          # 可加入 PATH 的 POSIX 启动脚本
  haifa-agent.jar       # 包含全部运行依赖的 shaded JAR
  haifa-coding.yaml     # 无密钥的安全默认配置
```

默认发布到用户目录 `~/.haifa-agent/coding/`：

```bash
./scripts/package-local-coding-agent.sh
export PATH="$HOME/.haifa-agent/coding:$PATH"
export DEEPSEEK_API_KEY="<secret>"

cd /path/to/any/project
haifa-coding
```

也可以将其他绝对路径作为第一个参数，覆盖默认发布目录。

把 `PATH` 配置写入 `~/.zshrc` 或 `~/.bashrc` 后可长期使用。`haifa-coding` 不切换目录，且 Java
入口未收到 `--workspace` 时默认使用进程当前目录，所以从哪个项目目录发起，该目录就是 Workspace。
发行配置只使用 `env://DEEPSEEK_API_KEY`，不包含密钥，默认保持
`approval=ask`、`host-guarded + network allow + shell auto` 和内存存储。可通过
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
Workspace。发行目录和 YAML 不包含模型凭据。打包入口使用 `-DskipTests`；它只生成发行制品，不替代
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
```

构建后也可以将 `bin` 目录加入 `PATH`，使用 `haifa-cli.ps1` 启动。

`--terminal` 与 `-m/--message` 不能同时使用。非交互、`dumb` 或不支持的终端会快速返回稳定的
`TUI_UNAVAILABLE`。同一规范化且非符号链接的 Workspace 会生成带版本 namespace 的稳定
Project/Workspace 身份；绝对路径不进入 Prompt、Client Event、JSONL 或普通错误输出。

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
  default: deepseek-v4-flash
  providers:
    - id: deepseek
      displayName: DeepSeek
      dialectId: deepseek-openai-chat
      dialectVersion: "1.0"
      nativeStreaming: true
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      models:
        - id: deepseek-v4-flash
          displayName: DeepSeek V4 Flash
          providerModelId: deepseek-v4-flash
        - id: deepseek-v4-pro
          displayName: DeepSeek V4 Pro
          providerModelId: deepseek-v4-pro
tools:
  enabled: [file.list, file.stat, file.read, file.search, file.create, file.write, execution.run, web.search, web.fetch]
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
execution:
  provider: host-guarded
  network: allow
  shell: powershell
persistence:
  mode: SQLITE
  databasePath: D:\haifa-agent-config\data\coding-terminal.db
  protectorRef: env://HAIFA_CONTINUATION_KEY
```

也可使用可信多模型配置；内部 `id` 与供应商 `providerModelId` 分离：

```yaml
models:
  default: deepseek-v4-flash
  providers:
    - id: deepseek
      displayName: DeepSeek
      dialectId: deepseek-openai-chat
      dialectVersion: "1.0"
      nativeStreaming: true
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      models:
        - id: deepseek-v4-pro
          displayName: DeepSeek V4 Pro
          providerModelId: deepseek-v4-pro
        - id: deepseek-v4-flash
          displayName: DeepSeek V4 Flash
          providerModelId: deepseek-v4-flash
    - id: aliyun-bailian
      displayName: Alibaba Cloud Bailian
      dialectId: aliyun-bailian-openai-chat
      dialectVersion: "1.0"
      nativeStreaming: true
      workspaceId: workspace-id
      region: cn-beijing
      credentialRef: env://DASHSCOPE_API_KEY
      models:
        - id: bailian-qwen-plus
          displayName: Qwen Plus
          providerModelId: qwen-plus
    - id: openai
      displayName: OpenAI
      dialectId: openai-chat-completions
      dialectVersion: "1.0"
      nativeStreaming: false
      endpoint: http://localhost:30000/v1
      credentialRef: env://OPENAI_API_KEY
      models:
        - id: openai-gpt-5.6-luna
          displayName: GPT-5.6 Luna
          providerModelId: gpt-5.6-luna
```

旧 `model` 配置仍按单模型读取。`--model`/`HAIFA_MODEL_ID` 只能选择已注册的内部 ID，不能临时
注入 Endpoint 或 Credential；未知 ID 会 fail closed。

Provider 是一级接入实例：Endpoint、Credential、百炼 Workspace/Region 只配置一次；其 `models`
是该 Provider 可用的模型列表。模型 `id` 是产品内全局唯一选择 ID，`providerModelId` 是供应商实际
模型或部署名称。每个 Provider 必须显式配置 `dialectId`、`dialectVersion` 和 `nativeStreaming`；
Coding Agent 不根据 Provider ID 推断协议。严格兼容 OpenAI Chat Completions 的第三方 HTTPS
Provider 可使用任意内部 ID，并复用 `openai-chat-completions`，无需修改 transport。

`host-guarded + allow` 以当前 Windows 用户身份执行，允许普通宿主网络，也不能提供容器级文件隔离；
只应对自己检查并信任的测试 Workspace 使用。模型与 Web Provider 调用可能计费。密钥只通过
`env://...` 注入，不写入配置；`HAIFA_CONTINUATION_KEY` 必须是跨重启稳定的 Base64 32 字节 AES key。

ConPTY 离线验收可在 CLI 子进程中显式设置 `HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL=true`，此开关
只允许 `http://localhost`、`http://127.0.0.1` 或 IPv6 loopback Endpoint，不能放宽外部 HTTP
Provider。普通运行不应设置该变量。

发行配置中的 OpenAI 第二 Provider 使用 `http://localhost:30000/v1`，因此启动 Coding Agent 前需
设置 `OPENAI_API_KEY`，并仅为该本机 loopback 端点设置
`HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL=true`。默认模型仍是 `deepseek-v4-flash`；使用
`--model openai-gpt-5.6-luna`、`HAIFA_MODEL_ID=openai-gpt-5.6-luna` 或空闲 Session 的模型选择入口切换。

## 安全 Trace

CLI 可实时订阅现有 `RuntimeTraceEvent`，不需要启用 `--verbose`：

最终失败输出使用 `[AgentErrorCode] 安全默认文案`，下一行显示可选 Diagnostic ID；非 Trace
输出不包含 Java 异常、Provider 原文或 Stack Trace。

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
  default: deepseek-v4-flash
  providers:
    - id: deepseek
      displayName: DeepSeek
      dialectId: deepseek-openai-chat
      dialectVersion: "1.0"
      nativeStreaming: true
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      models:
        - id: deepseek-v4-flash
          displayName: DeepSeek V4 Flash
          providerModelId: deepseek-v4-flash
        - id: deepseek-v4-pro
          displayName: DeepSeek V4 Pro
          providerModelId: deepseek-v4-pro
tools:
  enabled: [file.list, file.stat, file.read, file.search, file.create, file.write, file.delete, file.move, execution.run]
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
execution:
  provider: host-guarded
  network: allow
  shell: auto
  defaultTimeoutMillis: 120000
  maxTimeoutMillis: 1800000
  maxOutputLines: 2000
  maxOutputBytes: 51200
  maxProcesses: 8
  inheritEnvironment: [PATH, PATHEXT, HOME, USERPROFILE, TMP, TEMP, SystemRoot, SystemDrive, WINDIR, ComSpec, HOMEDRIVE, HOMEPATH, APPDATA, LOCALAPPDATA, ProgramData, ProgramFiles, ProgramW6432, PUBLIC, PSModulePath, JAVA_HOME, MAVEN_OPTS, GRADLE_USER_HOME]
  extraPathPolicies: []
runtime:
  maxIterations: 50
  maxToolCalls: 32
  maxWallTimeMillis: 300000
persistence:
  mode: MEMORY
```

`persistence.mode` 只允许 `MEMORY`、`SQLITE` 和 `SQLITE_WITH_JSONL`；默认 `MEMORY` 保持现有一次性
CLI 行为。持久模式示例：

```yaml
persistence:
  mode: SQLITE_WITH_JSONL
  databasePath: D:\haifa-agent-data\runtime.db
  transcriptRoot: D:\haifa-agent-data\transcripts
  protectorRef: env://HAIFA_CONTINUATION_KEY
  busyTimeoutMillis: 5000
  maximumPayloadBytes: 1048576
```

数据库与 transcript 路径必须是绝对路径，父目录/Transcript 目录必须预先受控创建。`SQLITE` 不会创建
JSONL；JSONL 从不参与恢复。`protectorRef` 只保存环境变量引用，变量值必须是 Base64 编码的 32 字节
AES key，且必须由用户的 Secret Manager 或环境注入并在重启间保持稳定。缺失、临时生成或长度错误都会使
持久模式在启动期 fail closed。对应环境变量覆盖为 `HAIFA_PERSISTENCE_MODE`、
`HAIFA_SQLITE_DATABASE_PATH`、`HAIFA_TRANSCRIPT_ROOT` 和
`HAIFA_CONTINUATION_PROTECTOR_REF`。

`tools.enabled` 使用内部点号名称；CLI 向模型披露时会映射为 `file_list`、`file_read`、`file_write`、`execution_run` 等 Provider-safe function name。`execution.run` 接收完整命令文本、Workspace 相对工作目录和 timeout；任何本机已安装且可由配置 Shell 解析的普通 CLI 都走同一生产路径，文档中的具体命令仅是非穷举示例。

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
已存在、可读、非符号链接的绝对目录。Source 会有界递归穿过分类目录，并把首个包含 `SKILL.md`
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
`web.fetch`，但当前 `web.fetch.provider` 只能是 `aliyun`。Provider 不读取环境变量；CLI 根据
`env://` 引用在启动期把密钥写入进程内加密 Credential Store，Runtime 在实际 Tool 调用期签发短期
`CredentialLease`。网络与凭据 Tool 默认仍按 `approval.mode` 进入审批策略，不会自动切换 Provider
或在失败时返回示例内容。

CLI 的 DeepSeek 和百炼冻结配置均强制关闭 thinking，并通过 Runtime output listener 实时打印安全的 answer delta；
reasoning 原文不会进入终端。使用 `--verbose` 时只会打印供应商报告的 reasoning token 计数，不记录或展示
reasoning 内容。

百炼 Provider 配置必须提供 `workspaceId`，`region` 缺省为 `cn-beijing`。CLI 不接受任意百炼主机，
而是固定推导 `https://{workspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1`。Provider、
Credential 和模型列表必须通过 `models.providers` 显式配置；`--model` 或 `HAIFA_MODEL_ID` 只负责
从已配置列表中选择模型。

`mcp.servers` 在 CLI 启动时连接并发现远端工具。每个 Server 必须使用稳定的小写 `id`、显式 `allowedTools` 和唯一 `aliasNamespace`；示例工具向模型披露为 `utility_time_now`、`utility_calculate`。发现不到、Schema 不兼容或不在本地审核策略中的配置工具会使启动失败，不会静默降级。

`policyProfile: conservative` 可用于任意显式 allowlist，但默认按高风险、未知幂等性和始终审批处理。`policyProfile: utility` 只接受 `CodingAgentMcpProfile` 已审核的 Utility 子集。生产 Server 必须使用 HTTPS；`allowLoopbackHttp: true` 只允许 `127.0.0.1` 或 `localhost` 开发端点。当前 CLI MCP 装配只支持无认证 Streamable HTTP，Credential 注入和 stdio 尚未开放为 CLI 配置。

写文件、删除文件、移动文件和 Shell 命令默认要求控制台确认。Shell 审批显示完整 command、逻辑 workdir、timeout、Shell 类型及 Host 非强隔离提示。`--approval auto` 仅适用于用户明确信任的本地工作区，仍经过 Broker、Workspace capability、Profile、环境和审计；`--approval deny` 会在 Catalog freeze 前移除 `execution.run`，模型不可见，底层授权仍 fail closed。

`execution.shell` 支持 `auto`、`bash` 和 `powershell`。自定义 Shell 必须通过本地配置中的绝对 `shellPath` 提供，不能来自 Tool 参数。环境配置只保存允许继承的名称；默认不继承 API Key、`*_TOKEN`、`*_SECRET`、云凭据或代理凭据。命令输出实时脱敏展示，最终模型结果默认限制为最后 2000 行且最多 50KB；较大分通道输出通过 Output Ref 访问。CLI timeout 与 Ctrl+C 会发送 Runtime CANCEL，并有界等待 Broker 收敛进程树。

当前已包含 tui4j Terminal、真实 `/resume` 搜索、Session 重命名/归档/逻辑删除、线性历史
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
