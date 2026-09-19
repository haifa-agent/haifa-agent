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

## Transport 与 Protocol Version

当前 MCP Integration 只支持模块实现与测试明确覆盖的 Transport / Protocol Version。

不能因为新版 MCP Specification 已经定义某项能力，就推断当前 Haifa Agent 已经支持。

精确兼容范围请看 [MCP 模块 README](../../haifa-agent-integrations/haifa-agent-mcp/README.md)。

## stdio

stdio MCP Server 通过 Execution Broker 托管，而不是直接把原始 Process handle 交给 Model。

这样可以把 Process Lifetime、Cancellation、Output Limit 与 Host Execution Rule 保持在同一个边界。

## 什么时候使用 MCP

当外部系统已经拥有成熟 MCP Server / Tool Contract 时，MCP 很合适。

如果能力只属于当前 Java 应用，而且直接写一个 Typed Java Tool 更简单，就没有必要为了“通用化”额外引入 MCP。
