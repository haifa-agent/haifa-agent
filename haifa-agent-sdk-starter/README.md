# Haifa Agent SDK Starter

`McpToolPlatforms.McpToolPlatform.toolNames()` 返回连接时完成协议协商、发现及 allowlist 筛选后
实际注册的 SDK Tool alias（包含真实 prefix 映射）的不可变 `Set<String>`。读取不重新连接、发现或
调用 `applyTo`，关闭后该快照仍可读取；空声明与 optional 失败均返回空集。旧第三方实现默认拒绝该
查询，不能用猜测名称替代真实注册事实。该方法只公开名称，不公开 schema、Provider 或凭据；
已发现名称不等于执行授权，调用仍进入既有 Runtime/Policy 边界。

面向首次接入者的纯 Java 安全默认装配。Starter 默认使用 DeepSeek V4 Flash、环境变量
`DEEPSEEK_API_KEY`、进程内 Runtime Persistence 和 Conversation Store，不启用文件、Shell、Git、
Web、Memory、Artifact 或 Execution。MCP 默认同样不启用，只有显式声明 `mcpServer(...)` 时才作为
MCP Client 连接远端服务器。它显式安装 `PolicyPresets.standardApproval()`（关键风险拒绝、副作用
动作询问、其余允许），SDK 本身不再隐式构造任何 Policy 规则。

可信宿主可以通过 `model(OpenAiCompatibleModelConfiguration)` 减少现有 OpenAI-compatible Integration 的
装配样板，也可以继续通过高级 `model(adapter, snapshot)` 注册模型；两种入口都支持多个 Provider/模型，
并通过 `defaultModel(modelId)` 选择默认模型。自定义目录会替代内置 DeepSeek 目录；Conversation 命令
使用已注册 model ID 作为可信 `runProfileId` 选择后续 Run，不能从 Prompt 注入 endpoint 或 Credential。

类型化配置生成同一 `ResolvedModelSnapshot` 和精确 Adapter coordinate；模型调用选项进入 Snapshot digest，
请求超时进入 Starter 的冻结 Run Profile。它不提供发现、fallback、健康路由或动态 Catalog。百炼/方舟
仍使用各自受治理工厂，原二参数入口继续服务完全自定义 Adapter。

有 `DEEPSEEK_API_KEY` 时，第一个 Agent 只需要一次构建、一次调用：

```java
import io.haifa.agent.starter.HaifaAgentStarter;

try (var haifa = HaifaAgentStarter.create()) {
    System.out.println(haifa.chat("Hello, Java!").await().text());
}
```

默认 name、instructions、模型和进程内 Store 都由 Starter 提供；需要改变行为时再显式配置，不让
Hello World 承担生产装配概念。

结构化最终结果使用同一个 Starter 和 Runtime 路径：

```java
public record TripPlan(String city, int days, List<String> activities) {}

var response = agent.chat("Plan a two-day trip.", TripPlan.class).await();
TripPlan plan = response.value();
```

注册模型必须声明 `ModelCapability.STRUCTURED_OUTPUT`，并由其 Adapter 实现对应协议映射。Runtime 在最终
回答上校验冻结的 record Schema，持久化成功后 SDK 才解码；Tool Loop 仍可先返回 Tool Call，不提供
类型化 partial stream。当前 API 未声明 Stable。

## MCP Client

Starter 可以作为 **MCP Client / MCP Tool Consumer** 消费远端 MCP Server。它不提供 MCP Server、
Tool / Resource / Prompt 对外发布能力。

```java
var search = McpServerSpec
        .streamableHttp("enterprise-search", URI.create("https://partner.example.com/mcp"))
        .allowTools("search_courses", "search_policies", "search_jobs")
        .toolNamePrefix("enterprise")
        .readOnly()
        .required();

try (var agent = HaifaAgentStarter.builder()
        .name("enterprise-search-agent")
        .instructions("You are an enterprise search assistant.")
        .mcpServer(search)
        .build()) {
    System.out.println(agent.chat("杭州有哪些 AI Agent 岗位？").await().text());
}
```

`McpServerSpec` 是不可变声明，每个配置方法都返回新实例。它只表达 MCP Client 真正需要声明的内容：
具名 connection、Streamable HTTP endpoint、显式 Tool allowlist、稳定 Tool name prefix、本地治理
preset、required/optional 以及超时和 Credential 引用。`McpConnectionManager`、
`McpToolDiscoveryService`、`McpToolDefinitionMapper`、`McpToolProvider`、`ToolCatalogBuilder` 等
Integration 内部模型不进入公共 API。

MCP Client 的公共面只有 `McpServerSpec`、`McpServerRequirement` 和
`HaifaAgentStarterBuilder.mcpServer(...)`；connect / discover / 本地审查由 package-private 装配件完成，
不作为扩展点暴露。

安全默认值：

- **没有 allow-all**。必须用 `allowTools(...)` 显式列出远端 Tool 名；声明为空会直接构建失败。
- **稳定 Tool 名**。`toolNamePrefix("enterprise")` 产生 `enterprise_search_courses` 等确定名字，
  不使用任何与连接顺序相关的生成规则。connection 身份与 Tool 命名是两个概念：connection name 足够短时
  自动推导前缀，较长时用 `toolNamePrefix(...)` 或
  `streamableHttp(name, endpoint, toolNamePrefix)` 显式声明。
- **冲突 fail fast**。MCP ↔ MCP 与 MCP ↔ Java Tool 的 Tool 名冲突都以 `TOOL_ALIAS_CONFLICT`
  终止构建，不静默覆盖。
- **默认保守治理**。未声明 `readOnly()` 时导入的 Tool 为 HIGH risk、UNKNOWN idempotency、
  网络访问加外部系统变更、强制 Approval。`readOnly()` 是**本地可信声明**（LOW risk、IDEMPOTENT、
  仅 NETWORK_ACCESS、Approval 交给 Policy），不是远端 MCP Server 的自我声明。
- **HTTPS**。非 loopback 端点必须是 HTTPS；本地开发可用 `allowLoopbackHttp()`。endpoint 不接受
  query 和 fragment：2025 线 Transport 按 origin + raw path 路由，带 query 会被静默丢弃。
- **Credential 不入 Spec**。`bearerTokenFromEnvironment("PARTNER_MCP_TOKEN")` 或
  `header(name, environmentVariable)` 只保存环境变量名；亦支持动态凭据供应 `bearerToken(Supplier<String>)`
  与 `header(name, Supplier<String>)`。密钥交由 Credential 边界持有，动态 Token 通过 Scoped Redaction
  随轮换动态纳管与卸载，并在 Agent 关闭时统一清理。密钥不进入 Tool Definition、诊断或日志。
- **保留 Header**。Transport 自己拥有 `Content-Type`、`Accept`、`MCP-Protocol-Version`、`Mcp-Method`、
  `Mcp-Session-Id`、`Last-Event-ID`、`Host`、`Content-Length`、`Connection` 等头，Credential API 不能
  覆盖或追加它们；`X-Api-Key` 这类自定义头正常可用。
- **确定性 binding**。多个 Credential Header 按规范化名称排序进入 MCP server binding digest，因此同一份
  声明在任何 JVM 都得到同一个 provider binding reference，冻结 Run 恢复后不会误判 binding 漂移。

`required()`（默认）在连接失败、协议不兼容、allowlist 中的 Tool 缺失、本地审查失败、Tool 名冲突或
Schema 非法时 fail closed；`optional()` 时该 Server 不贡献任何 Tool，Agent 仍可启动，并在
`agent.diagnostics()` 中给出 `MCP_SERVER_UNAVAILABLE` 一类安全诊断。任何情况下都不会注册不可用 Tool。

生命周期由 Agent 持有：`agent.close()` 释放全部 MCP 连接和 HTTP 资源，构建失败时同样释放，重复
`close()` 安全。Java Tool 与 MCP Tool 进入同一次 Tool Catalog freeze，不做 frozen catalog 合并。

Starter 的 `standardApproval()` preset 对任何带 NETWORK_ACCESS 的 Tool 都要求 Approval，因此 MCP Tool
调用会产生一次 Interaction。宿主用 `agent.runs().pendingInteraction(runId)` 和
`agent.runs().respond(...)` 响应；需要无人值守时应通过 `haifa-agent-sdk` 显式装配自己的 Policy 规则。

0.1.2-SNAPSHOT Native MCP Client 只提供 Streamable HTTP；stdio 需要 Execution Broker，暂不在 Starter 公开。

### 自定义装配：把声明式 MCP 接入任意 Builder

进程内 `HaifaAgentStarter` 固定装配内存 Persistence/Conversation。产品若已自有持久化与 Conversation
组件（例如 SQLite），可用 `McpToolPlatforms.connect(...)` 得到同一个声明式 MCP 贡献，再接入任意
`HaifaAgentBuilder`：

```java
var mcp = McpToolPlatforms.connect(List.of(search, jobs), tenant, principal);
try {
    HaifaAgentBuilder builder = HaifaAgents.builder(profile)
            .persistence(sqlite.persistence())
            .conversation(sqlite.conversation());
    mcp.applyTo(builder); // Tool registration、managed resource、Credential 与诊断一次完成
    return builder.build();
} catch (RuntimeException | Error failure) {
    mcp.close();
    throw failure;
}
```

`connect` 走与 Starter 相同的 `SdkMcpClientFactory` 与同一个 package-private 装配路径，返回值只暴露
`applyTo(HaifaAgentBuilder)` 与 `close()`；空列表是 no-op，不覆盖调用方已有的 Credential。required Server
失败时释放已开连接并抛出，optional Server 失败只贡献安全诊断。成功构建后由 Agent 持有并在 `close()` 释放；
`applyTo` 只能调用一次。

`applyTo` 会占用 builder 唯一的 Credential 槽：若 builder 已设置了另一个 Credential 贡献，`applyTo` 在添加任何
Tool、resource 或诊断之前抛 `IllegalStateException`，builder 保持原状，平台仍未应用、可交给其它 builder 或关闭；
`applyTo` 之后再设置不同的 Credential 贡献同样失败。需要与宿主自有凭据并存时，请提供一个组合 broker，不要依赖
后写覆盖。若 `HaifaAgentBuilder.build()` 在装配中途失败，平台不会被自动关闭，调用方须自行 `close()`。

默认 instructions 只是 Quickstart fallback；使用它时 `agent.diagnostics()` 包含
`DEFAULT_INSTRUCTIONS_IN_USE`，显式调用 `instructions(...)` 后该诊断消失。`name` 仅用于展示和
Conversation display name，不进入 Prompt 或选择逻辑；Agent `description` 暂不暴露。多轮、重试、
revision、取消和事件订阅继续使用显式
Conversation/Run API。

运行前设置 `DEEPSEEK_API_KEY`。该入口会访问真实 DeepSeek API 并产生费用。Starter 的进程内状态在
进程退出后丢失；生产系统应通过 `haifa-agent-sdk` 显式装配持久化、可信 Caller、Policy、Credential
和所需 Capability。

验证：

```bash
./mvnw -pl :haifa-agent-sdk-starter -am test
./mvnw -pl :haifa-agent-sdk-starter -am -Prelease verify
```

真实模型测试只有同时设置 `HAIFA_DEEPSEEK_LIVE_TEST=true` 与 `DEEPSEEK_API_KEY` 才运行。
