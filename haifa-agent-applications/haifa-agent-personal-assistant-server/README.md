# Haifa Personal Assistant Server

Server 兼容旧 `haifa.personal.model`，并支持 `haifa.personal.models` 受信列表和
`default-model-id`。`/api/v1/models`、Bootstrap 和 Conversation 只返回脱敏信息；Endpoint、
Credential、`providerModelId`、Adapter 和完整 Snapshot 不进入浏览器。模型偏好保存在 Personal
SQLite 中并可跨重启恢复；deterministic acceptance model 不能混入 production 可选列表。

Personal Assistant 的本机 Spring Boot WebFlux 交付模块。默认只监听
`127.0.0.1:20001`，本地确定性 MCP Stub 使用 `127.0.0.1:20002`，也可显式配置为更高端口。
端口冲突会使启动失败，不会自动换端口。

Server 负责：

- 显式装配 Product Profile、Model、SQLite、Policy、Memory、Tool、Skill 和 MCP；
- 按配置启用公共 `haifa-agent-web` 的 Aliyun IQS Search/Fetch，并把环境变量凭据绑定到
  Runtime `CredentialBroker`，不把 Key 放入 Profile、Tool Definition 或日志；
- `/api/v1` 版本化 HTTP DTO、OpenAPI、显式 Mapper 和稳定安全错误；
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

诊断树直接读取同一 SQLite 事实源，并在解码前校验各 payload 的实际字节哈希。它展示冻结 Agent
指令/模型配置、完整 Prompt/Message、Attempt、Step、Tool 参数与结果、Checkpoint、Interaction、
Skill、Runtime Event 和错误原文，用于快速定位单次 Run 的失败节点。所有接口均为 GET、只读、
`no-store`，仍受 loopback Host/Origin 边界约束；该能力不会出现在 `/api/v1/bootstrap`，普通
Personal Assistant 页面也没有入口或 Client 接口。

Server 不构建、不复制也不托管 React Web；`/` 和前端 history 路由返回 `404`。独立的
`haifa-agent-personal-assistant-web` 在 `127.0.0.1:20000` 提供 SPA，浏览器直接访问本
Server。CORS 只允许 loopback `20000` Origin，且不启用浏览器凭据；Host/Origin/CSRF、
幂等键和版本校验仍然生效。

生产默认使用远程 OpenAI-compatible Model。离线确定性 Model 必须同时显式设置：

```powershell
$env:HAIFA_PERSONAL_MODEL_MODE='deterministic'
$env:HAIFA_PERSONAL_ALLOW_DETERMINISTIC='true'
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
[`REAL_ENVIRONMENT.md`](REAL_ENVIRONMENT.md)。

## Process logging

The server uses Spring Boot's SLF4J/Logback logging stack. At `INFO`, it records safe operational milestones for Run acceptance and status changes, interaction/approval state, Tool and execution activity, and model call start/completion/failure with token counts and elapsed time. Normalized model failures additionally include their safe category, retryability, HTTP status, provider code, safe message, and stack trace. Logs intentionally exclude full prompts, assistant text, Tool arguments, command or script content, credentials, raw provider responses, result bodies, and messages from unclassified exceptions.
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
