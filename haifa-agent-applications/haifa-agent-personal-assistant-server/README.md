# Haifa Personal Assistant Server

Personal Assistant 的本机 Spring Boot WebFlux 交付模块。默认只监听
`127.0.0.1:20001`，本地确定性 MCP Stub 使用 `127.0.0.1:20002`，也可显式配置为更高端口。
端口冲突会使启动失败，不会自动换端口。

Server 负责：

- 显式装配 Product Profile、Model、SQLite、Policy、Memory、Tool、Skill 和 MCP；
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
GET /v1/admin/sessions
GET /v1/admin/sessions/{sessionId}/runs
GET /v1/admin/sessions/{sessionId}/runs/{runId}/tree
```

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
