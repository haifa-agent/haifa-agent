# Haifa Agent MCP Client Integration

本模块将显式固定到 `2025-03-26`、`2025-06-18`、`2025-11-25` 或 `2026-07-28` 的 MCP Tool 映射为普通 `ToolProvider`；`2024-11-05` 和其它已知日期范围内的未知版本 fail closed。严格符合 `yyyy-MM-dd`、日期有效且晚于 `2026-07-28` 的版本会被识别为待适配版本，连接时以 `MCP_PROTOCOL_VERSION_PENDING_ADAPTATION` 和“版本XXX未适配，即将适配”提示终止，不发起猜测性协议交互。2025 系列封装官方 MCP Java SDK 2.0.0 的 `mcp-core` 与 `mcp-json-jackson2`，2026 协议在同一 Integration 边界内使用独立的无会话 JSON-RPC 实现；SDK、Jackson 和 Reactor 类型不能进入 Tool、Runtime、Credential 或 Execution 公共契约。

## 支持范围

- 2025 Streamable HTTP：精确版本协商、JSON/SSE POST response、`initialize`/`initialized`、显式分页 `tools/list`、`tools/call` 和幂等关闭。
- 2025 HTTP 会话与恢复：兼容 stateless/session server、GET 405、DELETE 405、`Last-Event-ID` 恢复；session 404 使旧连接失效，发现操作可在重新 initialize 后安全重试，已发送 Tool Call 不重放。
- 2026 Streamable HTTP：使用 `server/discover`，不发送 `initialize`、GET、DELETE 或 session header；每个 POST 同时携带标准 `_meta`、`MCP-Protocol-Version`、`Mcp-Method`，`tools/call` 携带安全编码的 `Mcp-Name` 和经校验的 `x-mcp-header` 参数头，并接受 JSON 或 request-scoped SSE response。
- 资源边界：JDK HttpClient 包装器在 SDK transport 下方强制 response header/body 字节预算；request/deadline/cancel 会终止受影响连接并保留 dispatch certainty。
- stdio：2025 会话协议和 2026 per-request metadata 协议都通过唯一 `ExecutionBroker.openManagedSession` 桥接换行分隔 JSON-RPC；MCP 模块不使用 `ProcessBuilder`。
- 本地治理：server allowlist、HTTPS/loopback Origin、单一下划线 Tool 名称、保守风险/副作用/审批、受限 Schema、分页/Tool/Schema/deadline 预算。
- 冻结恢复：`McpToolBindingSnapshot` 分别保存 server binding digest、remote definition digest 与本地 Tool definition hash，缺失或漂移时 fail closed。
- 内容映射：Text/structured content 映射到唯一 Core `ToolResult`；`isError` 保留为业务失败；媒体只允许经有界 externalizer 形成 `AssetRef`。
- 动态目录：`tools/list_changed` 经 `McpToolRefreshCoordinator` 去抖动后生成新的、待审查 candidate snapshot，不热改已有 Run；`CodingAgentMcpProfile` 给出保守 utility allowlist 示例。

Resources、Prompts、Sampling、Elicitation、Roots、Completion、Subscriptions、MCP Server Hosting、OAuth 浏览器授权和任意 server 自动发现不在本阶段范围。2026 的 `server/discover` 只校验已配置 server 的版本、身份和 Tools capability；`input_required` 结果以 `MCP_INPUT_REQUIRED_UNSUPPORTED` 明确拒绝。

## 安全与生命周期

HTTP 生产配置只允许 HTTPS；loopback HTTP 必须显式启用且 Origin 必须命中 allowlist。redirect 默认关闭。Bearer/API Key 只能从短期 `CredentialLease` 在 per-request credential context 中注入；连接池键只保存 server/tenant/principal/credential binding reference，不保存 Token、Header、Cookie 或 session id。

认证 discovery 使用 `CredentialOperationRequest` 的 `MCP_CONNECTION_INITIALIZE`/`MCP_DISCOVERY` 控制面语义，不伪造 RunId 或 Tool coordinate。Tool call 只消费 Runtime 已放入 `ToolInvocationRequest` 的 Lease。stdio 环境值在 ExecutionBroker 解析环境时才物化，进程关闭后 binding 立即撤销。

HTTP 401/403 不会把 SDK request snapshot 或凭据带入对外异常：未配置预共享 Credential 时映射为 `MCP_AUTH_FLOW_UNSUPPORTED`，已配置 Credential 被拒绝时映射为 `MCP_REAUTH_REQUIRED`。首版不实现浏览器 OAuth、Protected Resource Metadata discovery 或 Token 刷新。

`DISPATCHED` 只在 HTTP request customizer 完成凭据注入并即将发送，或 stdio frame 即将写入 managed session 时记录。初始化失败允许按 server policy 做有界抖动重连；已 dispatch、结果未知的 Tool call 不自动重放。

## 配置与装配

`McpServerDefinition.create(...)` 生成内容寻址的不可变 server binding。HTTP 使用 `StreamableHttpDefinition`，stdio 使用只包含逻辑 executable、固定 argv、逻辑 cwd 和 env allowlist 的 `StdioDefinition`。应用层按 server 注册 `McpToolProvider`，再用 `McpToolCatalogContribution` 把已审查候选加入现有 `ToolCatalogBuilder`。

远端发现不等于启用。Tool 必须同时通过本地 allowlist/denylist、本地下划线名称唯一性、风险元数据和 Schema import diagnostic；不可信 MCP annotations 不能降低本地策略。外部远端名保留在 MCP binding snapshot 中用于协议调用；本地名称不做字符转换，不能与 namespace 直接组成合法下划线名称时拒绝导入。

## 测试

默认测试完全离线：

```powershell
.\mvnw.cmd -pl :haifa-agent-mcp -am test
```

官方 TypeScript SDK 的 `examples/dual-era` 是可重复使用的 HelloWorld 兼容目标。构建依赖后启动 HTTP server，并把仓库路径与 endpoint 传给 opt-in Live IT；该测试会分别通过 HTTP 和 ExecutionBroker-backed stdio 对四个已适配版本执行 initialize/discover、`tools/list` 和 `greet`：

```powershell
git clone https://github.com/modelcontextprotocol/typescript-sdk.git D:\dev\software\modelcontextprotocol-typescript-sdk
Set-Location D:\dev\software\modelcontextprotocol-typescript-sdk
corepack prepare pnpm@10.26.1 --activate
pnpm install --frozen-lockfile
pnpm --filter @mcp-examples/dual-era... build
pnpm tsx examples/dual-era/server.ts --http --port 32128

Set-Location D:\workspace\haifa-agent
$env:HAIFA_OFFICIAL_MCP_HTTP_URL='http://127.0.0.1:32128/mcp'
$env:HAIFA_OFFICIAL_MCP_REPO='D:\dev\software\modelcontextprotocol-typescript-sdk'
.\mvnw.cmd -pl :haifa-agent-mcp -am -Dtest=OfficialDualEraMcpCompatibilityLiveIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Live IT 只在相应环境变量存在时运行；默认 `ci-fast` 不启动外部进程或访问该 endpoint。

真实 utility server 兼容测试只在 server 已由用户显式启动时运行。它校验 SDK 2.0 Client 对 SDK 0.18.3 Server 的 19 Tool 合同、`time_now`、`calculate` 和错误结果；Token 不得写入命令或日志：

```powershell
$env:HAIFA_UTILITY_MCP_TEST='true'
$env:HAIFA_UTILITY_MCP_URL='http://127.0.0.1:8091/mcp'
$env:HAIFA_UTILITY_MCP_ORIGIN='http://127.0.0.1:8091'
.\mvnw.cmd -Pci-integration -pl :haifa-agent-mcp -Dit.test=UtilityMcpCompatibilityLiveIT verify
```

若 server 要求认证，通过进程环境设置 `HAIFA_UTILITY_MCP_TOKEN`，不要把值放进命令历史、配置、fixture 或报告。默认 `ci-fast` 不访问该 server。
