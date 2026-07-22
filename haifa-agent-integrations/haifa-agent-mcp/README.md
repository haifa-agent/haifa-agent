# Haifa Agent MCP Client Integration

本模块将固定协议 `2025-11-25` 的 MCP Tool 映射为普通 `ToolProvider`。实现封装官方 MCP Java SDK 2.0.0 的 `mcp-core` 与 `mcp-json-jackson2`；SDK、Jackson 和 Reactor 类型不能进入 Tool、Runtime、Credential 或 Execution 公共契约。

## 支持范围

- Streamable HTTP：精确协议协商、JSON/SSE POST response、`initialize`/`initialized`、显式分页 `tools/list`、`tools/call` 和幂等关闭。
- HTTP 会话与恢复：兼容 stateless/session server、GET 405、DELETE 405、`Last-Event-ID` 恢复；session 404 使旧连接失效，发现操作可在重新 initialize 后安全重试，已发送 Tool Call 不重放。
- 资源边界：JDK HttpClient 包装器在 SDK transport 下方强制 response header/body 字节预算；request/deadline/cancel 会终止受影响连接并保留 dispatch certainty。
- stdio：通过唯一 `ExecutionBroker.openManagedSession` 桥接长驻 JSON-RPC 会话；MCP 模块不使用 `ProcessBuilder`。
- 本地治理：server allowlist、HTTPS/loopback Origin、alias、保守风险/副作用/审批、受限 Schema、分页/Tool/Schema/deadline 预算。
- 冻结恢复：`McpToolBindingSnapshot` 分别保存 server binding digest、remote definition digest 与本地 Tool definition hash，缺失或漂移时 fail closed。
- 内容映射：Text/structured content 映射到唯一 Core `ToolResult`；`isError` 保留为业务失败；媒体只允许经有界 externalizer 形成 `AssetRef`。
- 动态目录：`tools/list_changed` 经 `McpToolRefreshCoordinator` 去抖动后生成新的、待审查 candidate snapshot，不热改已有 Run；`CodingAgentMcpProfile` 给出保守 utility allowlist 示例。

Resources、Prompts、Sampling、Elicitation、Roots、Completion、MCP Server Hosting、OAuth 浏览器授权和任意 server 自动发现不在本阶段范围。

## 安全与生命周期

HTTP 生产配置只允许 HTTPS；loopback HTTP 必须显式启用且 Origin 必须命中 allowlist。redirect 默认关闭。Bearer/API Key 只能从短期 `CredentialLease` 在 SDK per-request customizer 中注入；连接池键只保存 server/tenant/principal/credential binding reference，不保存 Token、Header、Cookie 或 session id。

认证 discovery 使用 `CredentialOperationRequest` 的 `MCP_CONNECTION_INITIALIZE`/`MCP_DISCOVERY` 控制面语义，不伪造 RunId 或 Tool coordinate。Tool call 只消费 Runtime 已放入 `ToolInvocationRequest` 的 Lease。stdio 环境值在 ExecutionBroker 解析环境时才物化，进程关闭后 binding 立即撤销。

`DISPATCHED` 只在 HTTP request customizer 完成凭据注入并即将发送，或 stdio frame 即将写入 managed session 时记录。初始化失败允许按 server policy 做有界抖动重连；已 dispatch、结果未知的 Tool call 不自动重放。

## 配置与装配

`McpServerDefinition.create(...)` 生成内容寻址的不可变 server binding。HTTP 使用 `StreamableHttpDefinition`，stdio 使用只包含逻辑 executable、固定 argv、逻辑 cwd 和 env allowlist 的 `StdioDefinition`。应用层按 server 注册 `McpToolProvider`，再用 `McpToolCatalogContribution` 把已审查候选加入现有 `ToolCatalogBuilder`。

远端发现不等于启用。Tool 必须同时通过本地 allowlist/denylist、alias 唯一性、风险元数据和 Schema import diagnostic；不可信 MCP annotations 不能降低本地策略。

## 测试

默认测试完全离线：

```powershell
.\mvnw.cmd -pl :haifa-agent-mcp -am test
```

真实 utility server 兼容测试只在 server 已由用户显式启动时运行。它校验 SDK 2.0 Client 对 SDK 0.18.3 Server 的 19 Tool 合同、`time_now`、`calculate` 和错误结果；Token 不得写入命令或日志：

```powershell
$env:HAIFA_UTILITY_MCP_TEST='true'
$env:HAIFA_UTILITY_MCP_URL='http://127.0.0.1:8091/mcp'
$env:HAIFA_UTILITY_MCP_ORIGIN='http://127.0.0.1:8091'
.\mvnw.cmd -Pci-integration -pl :haifa-agent-mcp -Dit.test=UtilityMcpCompatibilityLiveIT verify
```

若 server 要求认证，通过进程环境设置 `HAIFA_UTILITY_MCP_TOKEN`，不要把值放进命令历史、配置、fixture 或报告。默认 `ci-fast` 不访问该 server。
