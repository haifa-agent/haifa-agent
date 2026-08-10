# Haifa Personal Assistant Server

The v1 Run response includes an optional authoritative Plan/Todo projection. Activity responses
use stable operation IDs plus durable event IDs, parent correlation, event time, and optional
requested/started/completed lifecycle timestamps. Existing Runs without a plan omit `plan` or return `null`.

The public activities endpoint projects bounded durable Model, Tool, Skill, and MCP
events. Model activities never include prompts, assistant text, endpoints, credentials,
or raw provider failures.

Server 只接受 `haifa.personal.model-providers` 受信 Provider 列表和显式
`default-model-id`，不支持旧的单模型 `haifa.personal.model` 配置。`/api/v1/models`、Bootstrap
和 Conversation 只返回脱敏信息；Endpoint、
Credential、`providerModelId`、Adapter 和完整 Snapshot 不进入浏览器。模型偏好保存在 Personal
SQLite 中并可跨重启恢复；deterministic acceptance model 不能混入 production 可选列表。

Provider 是接入实例，持有共享 Endpoint、Credential、`native-streaming` 和运行模式；每个 Provider
通过 `api-bindings` 声明一个或多个 API Style，再由 Model 的 `style` 精确引用。Binding 省略 dialect
时使用 `standard`，只有 Style 使用不同 Base URL 时才配置完整 endpoint 覆盖：

```yaml
haifa:
  personal:
    default-model-id: deepseek-chat-flash
    model-providers:
      - id: deepseek
        display-name: DeepSeek
        mode: remote
        native-streaming: true
        endpoint: https://api.deepseek.com
        credential-reference: env://DEEPSEEK_API_KEY
        api-bindings:
          - style: openai-chat-completions
            dialect: deepseek-openai-chat
          - style: openai-responses
            dialect: deepseek-openai-responses
          - style: anthropic-messages
            dialect: deepseek-anthropic-messages
            endpoint: https://api.deepseek.com/anthropic
        models:
          - id: deepseek-chat-pro
            display-name: DeepSeek Chat Pro
            provider-model-id: deepseek-v4-pro
            style: openai-chat-completions
            capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
            context-window: 131072
            max-output-tokens: 8192
          - id: deepseek-responses-flash
            display-name: DeepSeek Responses Flash
            provider-model-id: deepseek-v4-flash
            style: openai-responses
            capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
            context-window: 131072
            max-output-tokens: 8192
          - id: deepseek-anthropic-flash
            display-name: DeepSeek Anthropic Messages Flash
            provider-model-id: deepseek-v4-flash
            style: anthropic-messages
            capabilities: [TEXT_CHAT, TOOL_CALLING, REASONING]
            context-window: 131072
            max-output-tokens: 8192
      - id: local-openai
        display-name: Local OpenAI Responses Gateway
        mode: remote
        native-streaming: true
        endpoint: ${OPENAI_BASE_URL:http://127.0.0.1:30000/v1}
        credential-reference: env://OPENAI_API_KEY
        api-bindings:
          - style: openai-responses
        models:
          - id: local-openai-responses
            display-name: Local OpenAI Responses
            provider-model-id: ${OPENAI_MODEL_ID:gpt-5.6-luna}
            style: openai-responses
            capabilities: [TEXT_CHAT]
            context-window: 131072
            max-output-tokens: 8192
    allow-insecure-loopback-model: true
```

模型 `id` 是产品内全局唯一的选择与偏好 ID；`provider-model-id` 是发送给对应 Provider 的实际模型
或部署名称。Personal Assistant 不根据 Provider ID 推断协议。严格兼容既有 Style 的第三方 HTTPS
Provider 只需新增配置并省略 dialect；旧单模型、旧 dialect/version 字段和模型级连接字段不再接受。
DeepSeek Anthropic Messages 因 Base URL 与其余 Style 不同，在 Binding 上覆盖完整 `/anthropic` Endpoint；
Credential 与 `native-streaming` 仍只配置在 Provider。

`allow-insecure-loopback-model` 只允许显式的 `http` loopback 模型端点；任何外部 HTTP 地址仍会在
Server 装配期失败。凭据只通过 `env://OPENAI_API_KEY` 解析，不写入 YAML、日志或浏览器响应。默认模型是显式关闭 thinking 的
`deepseek-chat-flash`；Responses 与 Anthropic Messages 模型仍作为非默认的受信选项保留。本地中转当前只声明 `TEXT_CHAT`，因此不会出现在 Personal 所需
`TEXT_CHAT + TOOL_CALLING` 的可选列表中；Snapshot 仍按 `standard` Responses 冻结真实能力边界。

`IMAGE_INPUT` 是模型级显式能力，不根据 Provider ID 或模型名猜测。启用后，Conversation 请求可带
最多四个 `{kind: url|upload}` 图片输入。外部 URL 只接受受限 HTTPS；上传通过 `POST /api/v1/images`
写入 `<data-directory>/images`，单文件上限 10 MiB、目录上限 1 GiB，类型限 PNG/JPEG/WEBP/非动画
GIF。SQLite 与 Turn 只保存 opaque 引用、MIME、长度和摘要，不保存图片 Base64 或绝对路径。本阶段
没有下载、缩略图、OCR、单附件删除或自动过期任务。

Personal Assistant 的本机 Spring Boot WebFlux 交付模块。默认只监听
`127.0.0.1:20001`，本地确定性 MCP Stub 使用 `127.0.0.1:20002`，也可显式配置为更高端口。
端口冲突会使启动失败，不会自动换端口。

## Personal Mission Phase 1–4

Phase 4 advances the Personal Mission schema to V6 and persists authoritative model-token,
model-call, and Tool-call usage. Configured upper bounds are validated at startup and admission
stops only new Mission dispatch when the SQLite or Artifact store reaches its stop threshold;
running work remains observable and can converge. Readiness requires the single Dispatcher owner,
its first reconciliation, and a successful Artifact integrity check. Shutdown stops admission,
waits for the bounded convergence window, and leaves durable work recoverable when the window
expires.

The default Mission-wide model-token budget is 3,000,000 (configurable up to 4,000,000), and the independent
Tool-call budget is 360 (configurable up to 400). These cover a bounded multi-task Deep Research run, one automatic
task retry, and its final synthesis without widening any stage's explicit Tool allowlist.
The default Mission wall-clock budget is two hours. It is a product hard limit for serial multi-Task research, while
the deterministic acceptance profile keeps its explicit 30-minute test deadline.

Phase 3 adds product schema V5 for the frozen Mission-level Skill binding and uses shared Runtime
schema V7 Artifact metadata plus an application-owned, no-follow payload directory. Deep Research
uses only the approved `web.search` / `web.fetch` pipeline, validates canonical source identities,
citation closure, quote bounds and structured synthesis, then publishes exactly five owner-only,
hash-verified Artifacts and one idempotent final Conversation message. The deterministic offline
Stub exercises the same Tool/Skill/Runtime path; it is not a production network provider.

Server 提供 `/api/v1/missions` 的 create/list/get/snapshot、确认前完整计划 replace/regenerate、confirm
、cancel 和 blocked Task retry。写操作要求 `Idempotency-Key`，计划变更、确认和重试要求 `If-Match`；owner、Conversation 和
Planner Profile 均从可信 Server 上下文解析，浏览器不能注入 Skill、Provider、Credential 或路径。

Mission 使用独立的 Personal SQLite schema history。Mission、Plan revision、Task、Dependency、Event、
Outbox 和 Command 在同一产品 UoW 中提交；数据库约束保证一个 Conversation 最多一个活动 Mission，
触发器冻结已确认计划定义。Phase 2 用部分唯一索引保证全局最多一个活动 Task Attempt；OS 文件锁与
Dispatcher ownership heartbeat 使同一数据目录只能有一个 Dispatcher。产品 UoW 与 Runtime UoW 通过
稳定 dispatch key 和带请求摘要的 Runtime start 幂等绑定恢复，不宣称分布式 HA。Task Outbox 保存完整
且有界的冻结 Run Input；直接依赖结果及其 digest、Profile 和工具边界均进入同一个 payload digest，claim
时校验 Outbox/Attempt/digest 一致性，下游不会只等待依赖完成却丢失依赖结果。

只读运维事实由 `/v1/admin/missions/operations` 和
`/v1/admin/missions/upgrade-readiness` 提供；Actuator readiness 也包含 Personal Mission
Dispatcher、首次 reconciliation、容量和 Artifact 完整性状态。升级前必须使 active Mission、待发
Outbox 和未结算 Attempt 全部归零。MVP 不自动删除 Mission、Task、Run、Source 或 Artifact；容量告警后
由运维人员先完成备份和校验，再按明确的维护流程处理数据。

离线备份、校验和恢复通过可执行 Server JAR 运行。备份要求 Server 已停止、Dispatcher 文件锁可获取且
Mission Store 处于 quiescent 状态；恢复目标必须是全新目录。`product-digest` 取自启动接口的
`assemblyDigest`，`skill-binding` 使用 Mission 快照中冻结的完整 Deep Research Skill binding：

```powershell
java -jar .\target\haifa-agent-personal-assistant-server-0.1.0-SNAPSHOT.jar `
  mission-maintenance backup <data-dir> <backup-dir> <product-digest> "<skill-binding>"
java -jar .\target\haifa-agent-personal-assistant-server-0.1.0-SNAPSHOT.jar `
  mission-maintenance verify <backup-dir> - <product-digest> "<skill-binding>"
java -jar .\target\haifa-agent-personal-assistant-server-0.1.0-SNAPSHOT.jar `
  mission-maintenance restore <backup-dir> <fresh-data-dir> <product-digest> "<skill-binding>"
```

备份清单绑定产品摘要、Skill binding、Schema 版本和每个数据库/Artifact 文件的 SHA-256。校验与恢复会
检查清单、哈希、SQLite integrity/foreign keys、Run/Session 引用和 Artifact 引用；任何漂移均 fail
closed。该命令不是在线热备、在线恢复、自动迁移或回滚机制。

Server 负责：

- 显式装配 Product Profile、Model、SQLite、Policy、Memory、Tool、Skill 和 MCP；
- 装配 Personal Mission Store、Planner、Application Service 与版本化 HTTP/OpenAPI；
- 按配置启用公共 `haifa-agent-web` 的 Aliyun IQS Search/Fetch，并把环境变量凭据绑定到
  Runtime `CredentialBroker`，不把 Key 放入 Profile、Tool Definition 或日志；
- `/api/v1` 版本化 HTTP DTO、OpenAPI、显式 Mapper 和稳定安全错误；
- 图片上传的 magic-byte/MIME 校验、本地有界 Store，以及调用模型前的摘要复核；

普通 Run API 的 `error` 与 OpenAPI `ExecutionError` 对齐，包含稳定执行 code、安全 message、
category、retryability、有界 details、diagnosticId 和 occurredAt；请求错误仍使用独立的安全
API Error envelope。
- 完成态 Run 的 `recommend-questions` 可选辅助推理接口；POST 绑定 Conversation/Run 和
  `Idempotency-Key`，模型判定为快问快答、简单计算等闭合问题时返回空数组；
- Reactor Netty / Spring WebFlux HTTP；
- `Flux<ServerSentEvent<?>>` Run 流合并 durable Run/Tool/Interaction Activity 与 transient Assistant
  output；SSE ID 同时携带两套 source-local cursor 和进程 epoch，避免 sequence 冲突，并保留
  heartbeat、bounded overflow、终态关闭和断连订阅清理；
- 固定可信 Caller、Host/Origin/CSRF、请求体上限和安全响应头；
- Actuator liveness/readiness。

Server 另提供与普通产品 API 隔离的只读本机诊断面：

```text
GET /v1/admin/
GET /v1/admin/capabilities
GET /v1/admin/sessions
GET /v1/admin/sessions/{sessionId}/runs
GET /v1/admin/sessions/{sessionId}/runs/{runId}/tree
```

`capabilities` 返回产品组装时冻结的 Tool、已审核 MCP Server 和 Skill 注册快照，包括定义身份、版本与
摘要、风险和审批策略、Schema、资源声明、MCP 协议与导入工具、Skill 元数据与资源索引；不会返回
凭据值、MCP Session ID 或运行时 Lease。

诊断树直接读取同一 SQLite 事实源，并在解码前校验各 payload 的实际字节哈希。它展示冻结配置
引用、Attempt、Step、Tool/Checkpoint/Interaction 关联、Skill、Runtime Event、安全错误详情和
诊断编号，用于快速定位单次 Run 的失败节点；Prompt/Message 正文、Tool 参数与结果、Checkpoint
状态、Interaction 内容、原始 Provider 数据和 Stack Trace 始终隐藏。所有接口均为 GET、只读、
`no-store`，仍受 loopback Host/Origin 边界约束；该能力不会出现在 `/api/v1/bootstrap`，普通
Personal Assistant 页面也没有入口或 Client 接口。

Server 不构建、不复制也不托管 React Web；`/` 和前端 history 路由返回 `404`。独立的
`haifa-agent-personal-assistant-web` 在 `127.0.0.1:20000` 提供 SPA，浏览器直接访问本
Server。CORS 只允许 loopback `20000` Origin，且不启用浏览器凭据；Host/Origin/CSRF、
幂等键和版本校验仍然生效。

生产默认使用远程 OpenAI-compatible Model。离线确定性 Model 也必须按 Provider 及其模型列表
注册，并显式允许 deterministic 模式：

```yaml
haifa:
  personal:
    default-model-id: personal-test
    model-providers:
      - id: personal-local
        display-name: Local acceptance
        mode: deterministic
        allow-deterministic: true
        native-streaming: false
        endpoint: http://127.0.0.1:20999
        credential-reference: env://UNUSED
        api-bindings:
          - style: deterministic-chat
        models:
          - id: personal-test
            display-name: Personal test
            provider-model-id: personal-test
            style: deterministic-chat
            capabilities: [TEXT_CHAT, TOOL_CALLING]
            context-window: 8192
            max-output-tokens: 1024
```

Phase 3 的本机命令/脚本能力复用平台 Execution Broker 和 Host Guarded Sandbox。因为当前
Provider 会启动可信主机进程、不能保证强隔离或断网，Server 默认 fail closed；本机管理员必须
显式确认该部署边界：

```powershell
$env:HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED='true'
```

Personal 产品使用固定的 `personal-execution` Server 私有 Workspace，模型不能指定 cwd。默认单次 15 秒、最大 30 秒、
64 KiB / 1000 行输出和最多 4 个并发进程；可执行文件从可信 Server 配置和当前 OS 解析。三端 Host Guarded
复用公共 HOST_USER 环境解析器，显式提供真实用户 HOME，并保留 Windows `USERPROFILE/APPDATA/LOCALAPPDATA`
等受信任目录；HOME 不安全时进程启动前 fail closed。PA 使用 Execution Core 公共增量 Observer，只观察私有
Workspace 内逻辑变化，不扫描或记录 HOME、AppData、XDG 和包安装目录，也不把 Server 凭据注入子进程。每次调用仍必须经过 Runtime Interaction exact
approval；开关只确认 Provider 部署风险，不构成某次调用授权。

默认 MCP 模式为 `embedded-echo`，用于离线测试。连接已经单独启动的 loopback MCP 服务时，必须显式
切换为 `external` 并给出最小 Tool allowlist；Server 不会代替外部进程启动或扫描全局 MCP：

```powershell
$env:HAIFA_PERSONAL_MCP_MODE='external'
$env:HAIFA_PERSONAL_MCP_ENDPOINT='http://127.0.0.1:20002/mcp'
$env:HAIFA_PERSONAL_MCP_ALLOWED_TOOLS='calculate,time_now,unit_convert,weather_current'
$env:HAIFA_PERSONAL_MCP_SERVER_ID='haifa-utility'
$env:HAIFA_PERSONAL_MCP_DISPLAY_NAME='Haifa Utility MCP'
```

外部 endpoint 只接受 `http` loopback 地址和 `20002+` 端口。发现失败、Tool 缺失或本地审查失败都会
使 Server 启动失败；不会回退到 embedded echo。

Web Tool 默认关闭。启用后会同时装配 `web_search -> web.search` 和
`web_fetch -> web.fetch`，当前 Personal Profile 固定使用 Aliyun IQS：

```powershell
$env:HAIFA_PERSONAL_WEB_ENABLED='true'
$env:HAIFA_PERSONAL_WEB_CREDENTIAL='env://ALIYUN_IQS_API_KEY'
$env:ALIYUN_IQS_API_KEY='<aliyun-iqs-key>'
```

可信本地 Skill Source 的配置值是“包含各 Skill 子目录的根目录”。例如 finance 集合应配置为：

```powershell
$env:HAIFA_PERSONAL_SKILL_ROOT='D:\agents\hermes-agent\optional-skills\finance'
```

启动还必须提供可持久恢复的 32 字节 AES Key（Base64），不得记录该值：

```powershell
$env:HAIFA_PERSONAL_CONTINUATION_KEY='<base64-aes-256-key>'
java -jar .\target\haifa-agent-personal-assistant-server-0.1.0-SNAPSHOT.jar
```

OpenAPI 和健康检查：

```text
http://127.0.0.1:20001/api/v1/openapi.json
http://127.0.0.1:20001/actuator/health
```

Maven 只构建后端 executable JAR，不需要 Node.js/npm，也不读取相邻 Web 目录。前端构建和部署
命令见 `../haifa-agent-personal-assistant-web/README.md`。

真实 DeepSeek、外部 Utility MCP 和独立 Web 的可重复环境搭建方法见
[`REAL_ENVIRONMENT.md`](REAL_ENVIRONMENT.md)。PowerShell 与 POSIX Shell 入口都要求 Python 3；两者只负责
参数兼容和解释器发现，启动、健康检查、状态文件与安全停止逻辑统一由根目录
[`scripts/real_environment.py`](../../scripts/real_environment.py) 实现。

macOS 可直接使用与 Windows PowerShell 版本行为对齐的启动脚本：

```bash
./scripts/start-real-environment.sh

# 只校验将要停止的 PID、端口和进程身份
./scripts/start-real-environment.sh \
  --stop --dry-run

# 停止后重新构建并启动
./scripts/start-real-environment.sh --stop
./scripts/start-real-environment.sh --rebuild
```

Key、Utility MCP、Skill 和 Continuation Key 路径均可通过参数或专用环境变量覆盖；脚本不会把凭据
写入参数、状态文件或日志。

PowerShell 与 Bash 启动脚本共用同一个配置生成器。OpenAI 本机中转是可选 Provider：只有当前进程
（Windows 也回退到用户环境）同时提供 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL_ID` 时才
装配；三项全部缺失或配置不完整都不阻断 DeepSeek-only 启动，配置不完整时脚本会输出不含值的警告。
启用后，本机中转使用 standard dialect 的 OpenAI Responses API，Provider 持有共享 Endpoint、
CredentialRef 与 `nativeStreaming=true`，Binding 只声明 `style: openai-responses`。该模型当前只声明
`TEXT_CHAT`，因此不会进入要求 Tool Calling 的 Personal Assistant 可选模型目录。

## Process logging

The server uses Spring Boot's SLF4J/Logback logging stack. At `INFO`, it records safe operational milestones for Run acceptance and status changes, interaction/approval state, Tool and execution activity, and model call start/completion/failure with token counts and elapsed time. Known failures log stable codes and bounded safe attributes without a stack trace. Unexpected Throwables are available only to the explicitly configured internal diagnostic sink and are correlated by diagnostic ID. Logs intentionally exclude full prompts, assistant text, Tool arguments, command or script content, credentials, raw provider responses, result bodies, and messages from unclassified exceptions.
Invalid server-side argument failures are logged with correlation ID, HTTP method/path, exception type, and bounded stack origin while omitting the exception message and request content.
Execution diagnostics expose bounded `failureCode` and `dispatchState` values. They distinguish preflight rejection
from failures after a host process was actually launched without exposing command content or physical scratch paths.

## Trusted Skill script manifest

Trusted script auto-approval is a separate explicit opt-in. The manifest must be an external regular file and
must pin the reviewed package, registration, script, generated Tool, execution configuration, sandbox,
capability, network, subject, expiry, and revocation facts. It contains no credential or script source:

```powershell
$env:HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST='D:\secure-config\trusted-skill-scripts.yml'
.\scripts\start-real-environment.ps1 `
  -SkillRoot 'D:\agents\hermes-agent\optional-skills\finance' `
  -TrustedScriptManifest 'D:\secure-config\trusted-skill-scripts.yml'
```

For initial diagnostics, a script entry may be `REVOKED` with an all-zero expected Tool definition hash. The
server starts and Admin exposes the computed safe binding/digest metadata, but the script cannot be
auto-approved. After reviewing the exact package, script, fixed Tool Schema, runtime, sandbox, capabilities,
and hosts, the operator records the real hash, changes the grant to `ACTIVE`, and restarts. Any subsequent
drift returns that invocation to ordinary approval or rejection. Generic `execution.run` always keeps its
existing exact human approval.
