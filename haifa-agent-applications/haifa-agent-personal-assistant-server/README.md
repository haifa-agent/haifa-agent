# Haifa Personal Assistant Server

The public activities endpoint projects bounded durable Model, Tool, Skill, and MCP
events. Model activities never include prompts, assistant text, endpoints, credentials,
or raw provider failures.

Server 只接受 `haifa.personal.model-providers` 受信 Provider 列表和显式
`default-model-id`，不支持旧的单模型 `haifa.personal.model` 配置。`/api/v1/models`、Bootstrap
和 Conversation 只返回脱敏信息；Endpoint、
Credential、`providerModelId`、Adapter 和完整 Snapshot 不进入浏览器。模型偏好保存在 Personal
SQLite 中并可跨重启恢复；deterministic acceptance model 不能混入 production 可选列表。

Provider 是接入实例，持有 Endpoint、Credential 和运行模式；每个 Provider 再声明自己的可用模型
列表。例如 DeepSeek 与本机 OpenAI-compatible 端点可以同时注册：

```yaml
haifa:
  personal:
    default-model-id: deepseek-v4-flash
    model-providers:
      - id: deepseek
        display-name: DeepSeek
        mode: remote
        dialect-id: deepseek-openai-chat
        dialect-version: "1.0"
        native-streaming: true
        endpoint: https://api.deepseek.com
        credential-reference: env://DEEPSEEK_API_KEY
        models:
          - id: deepseek-v4-pro
            display-name: DeepSeek V4 Pro
            provider-model-id: deepseek-v4-pro
          - id: deepseek-v4-flash
            display-name: DeepSeek V4 Flash
            provider-model-id: deepseek-v4-flash
      - id: openai
        display-name: OpenAI
        mode: remote
        dialect-id: openai-chat-completions
        dialect-version: "1.0"
        native-streaming: false
        endpoint: http://localhost:30000/v1
        credential-reference: env://OPENAI_API_KEY
        models:
          - id: openai-gpt-5.6-luna
            display-name: GPT-5.6 Luna
            provider-model-id: gpt-5.6-luna
            image-input: true
    allow-insecure-loopback-model: true
```

模型 `id` 是产品内全局唯一的选择与偏好 ID；`provider-model-id` 是发送给对应 Provider 的实际模型
或部署名称。远程 Provider 必须显式配置 `dialect-id`、`dialect-version` 和 `native-streaming`；
Personal Assistant 不根据 Provider ID 推断协议。严格兼容 OpenAI Chat Completions 的第三方 HTTPS
Provider 可使用任意内部 ID，并复用 `openai-chat-completions`，无需修改 transport。

`allow-insecure-loopback-model` 只允许显式的 `http` loopback 模型端点；任何外部 HTTP 地址仍会在
Server 装配期失败。凭据只通过 `env://OPENAI_API_KEY` 解析，不写入 YAML、日志或浏览器响应。默认模型
仍是 `deepseek-v4-flash`，Personal 的模型 API/Selector 可把空闲 Conversation 切换到
`openai-gpt-5.6-luna`，只影响后续新 Run。

`image-input: true` 是模型级显式能力，不根据 Provider ID 或模型名猜测。启用后，Conversation 请求可带
最多四个 `{kind: url|upload}` 图片输入。外部 URL 只接受受限 HTTPS；上传通过 `POST /api/v1/images`
写入 `<data-directory>/images`，单文件上限 10 MiB、目录上限 1 GiB，类型限 PNG/JPEG/WEBP/非动画
GIF。SQLite 与 Turn 只保存 opaque 引用、MIME、长度和摘要，不保存图片 Base64 或绝对路径。本阶段
没有下载、缩略图、OCR、单附件删除或自动过期任务。

Personal Assistant 的本机 Spring Boot WebFlux 交付模块。默认只监听
`127.0.0.1:20001`，本地确定性 MCP Stub 使用 `127.0.0.1:20002`，也可显式配置为更高端口。
端口冲突会使启动失败，不会自动换端口。

Server 负责：

- 显式装配 Product Profile、Model、SQLite、Policy、Memory、Tool、Skill 和 MCP；
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
        endpoint: http://127.0.0.1:20999
        credential-reference: env://UNUSED
        models:
          - id: personal-test
            display-name: Personal test
            provider-model-id: personal-test
```

Phase 3 的本机命令/脚本能力复用平台 Execution Broker 和 Host Guarded Sandbox。因为当前
Provider 会启动可信主机进程、不能保证强隔离或断网，Server 默认 fail closed；本机管理员必须
显式确认该部署边界：

```powershell
$env:HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED='true'
```

Personal 产品使用 Server 私有 Workspace，模型不能指定 cwd。默认单次 15 秒、最大 30 秒、
64 KiB / 1000 行输出和最多 4 个并发进程；可执行文件从可信 Server 配置和当前 OS 解析。环境变量
采用最小 allowlist，不把 Server 凭据注入子进程。每次调用仍必须经过 Runtime Interaction exact
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

Windows 启动脚本从当前进程环境读取 `OPENAI_API_KEY`，缺失时再读取 Windows 用户环境，并将
OpenAI 第二 Provider 显式装配为 `openai-gpt-5.6-luna`。该本机中转冻结
`native_streaming=false`，上游使用同步 Chat Completions 返回权威 usage，产品侧仍通过模型契约桥接
有界 Content/Usage 事件。

## Process logging

The server uses Spring Boot's SLF4J/Logback logging stack. At `INFO`, it records safe operational milestones for Run acceptance and status changes, interaction/approval state, Tool and execution activity, and model call start/completion/failure with token counts and elapsed time. Known failures log stable codes and bounded safe attributes without a stack trace. Unexpected Throwables are available only to the explicitly configured internal diagnostic sink and are correlated by diagnostic ID. Logs intentionally exclude full prompts, assistant text, Tool arguments, command or script content, credentials, raw provider responses, result bodies, and messages from unclassified exceptions.
Invalid server-side argument failures are logged with correlation ID, HTTP method/path, exception type, and bounded stack origin while omitting the exception message and request content.

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
