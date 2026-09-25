# Skills 与 MCP

Skill 与 MCP 都可以扩展 Agent 的能力，但它们都不能绕过 Runtime 的 Capability 与 Safety 边界。

## Skills

Skill 是经过审查的 instruction / resource package。

Haifa Agent 支持基于 SKILL.md 的 Skill，包含冻结 identity、Metadata Disclosure、Activation 与受控 Resource Read。

Skill 可以帮助 Model 理解“如何完成某类任务”，但不会自动获得：

- Filesystem 权限；
- Process Execution；
- Network 权限；
- Credential；
- Approval bypass；
- 任意 Tool。

产品仍然负责决定某个 Run 最终可见哪些 Tool alias。

## MCP

MCP Integration 负责连接外部 MCP Server，发现 Tool，对 Metadata / Schema 做审查与映射，然后把允许的 Tool 导入本地 Tool Catalog。

因此，MCP 是 Tool Source，而不是第二套 Execution Runtime。

导入后的 MCP Tool 仍然进入和本地 Java Tool 相同的 Runtime Tool Pipeline。

Haifa 在这里只扮演 **MCP Client / MCP Tool Consumer**。它不提供 MCP Server Hosting，也不把本地 Tool、
Resource 或 Prompt 对外发布为 MCP。

## 纯 Java 接入

纯 Java 应用通过 `McpServerSpec` 声明要消费的 MCP Server，不需要直接使用 MCP Integration 的内部模型：

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

心智模型保持不变：

```text
Agent + Java Tool / MCP Tool = Tool
```

Java Tool 与 MCP Tool 进入同一次 Tool Catalog freeze。Tool 名冲突（MCP ↔ MCP、MCP ↔ Java Tool）一律
构建失败，不静默覆盖。没有 allow-all 导入模式；`readOnly()` 是本地可信声明，不是远端 Server 的自我
声明。`required()` 在不可用时 fail closed，`optional()` 时该 Server 不贡献任何 Tool 并给出安全诊断。

Native MCP Client 的生命周期属于 Agent：`agent.close()` 释放它打开的全部 MCP 连接与 HTTP 资源。

详见 [SDK Starter README](../../haifa-agent-sdk-starter/README.md)。

## Spring AI MCP Client 边界

Spring Boot 应用如果已经使用 Spring AI MCP Client Starter，未来应由一个独立的
`haifa-agent-spring` Adapter 从 `ToolCallbackProvider` 方向接入，由 Spring 继续拥有 connection、
transport、初始化与生命周期；Haifa 不对同一个 connection 再建立第二套 MCP 连接。

该 Adapter 尚未实现。当前 Core、Runtime、SDK、SDK Starter 与 MCP Integration 都不依赖 Spring AI，并由
Maven Enforcer 与 ArchUnit 约束保持这一边界。即使将来接入，Spring `ToolCallback` 也只是
“Tool definition + invocation adapter”，仍要经过 Haifa 本地 allowlist 与 risk / idempotency / approval
分类，不会因为来自 Spring 就自动可信。

### 已知权衡：Spring Starter 携带 Native MCP Client

为了让纯 Java Starter 只用一个依赖就获得 `.mcpServer(...)`，`haifa-agent-sdk-starter` 直接依赖
`haifa-agent-mcp`，因此依赖链是：

```text
haifa-agent-spring-boot-starter
        ↓
haifa-agent-spring-boot-autoconfigure
        ↓
haifa-agent-sdk-starter
        ↓
haifa-agent-mcp
        ↓
官方 MCP Java SDK 2.x
```

也就是说，即使 Spring 应用打算完全改用 Spring AI MCP Client，Haifa Spring Starter 仍然带着 Native MCP
Client 依赖链。当前无冲突：Haifa 与 Spring AI 都在 MCP Java SDK 2.x 这一代。

这是有意接受的产品权衡，但必须重新评估：官方 MCP Java SDK 2.x 对应 `2025-11-25` 线，3.x 已进入
`2026-07-28` spec 路线。**在 Spring AI MCP Client Adapter 落地之前，必须重新核对 MCP SDK 依赖收敛，
必要时把 Native MCP 依赖从 Spring 默认路径隔离出去。** 现在不为此再拆模块。

## Transport 与 Protocol Version

当前 MCP Integration 只支持模块实现与测试明确覆盖的 Transport / Protocol Version。

不能因为新版 MCP Specification 已经定义某项能力，就推断当前 Haifa Agent 已经支持。

精确兼容范围请看 [MCP 模块 README](../../haifa-agent-integrations/haifa-agent-mcp/README.md)。

## stdio

stdio MCP Server 通过 Execution Broker 托管，而不是直接把原始 Process handle 交给 Model。

这样可以把 Process Lifetime、Cancellation、Output Limit 与 Host Execution Rule 保持在同一个边界。

因为它需要 Execution Broker，`McpServerSpec` 当前只提供 Streamable HTTP；stdio 仍由自行装配 MCP
Integration 的应用使用。

## 什么时候使用 MCP

当外部系统已经拥有成熟 MCP Server / Tool Contract 时，MCP 很合适。

如果能力只属于当前 Java 应用，而且直接写一个 Typed Java Tool 更简单，就没有必要为了“通用化”额外引入 MCP。
