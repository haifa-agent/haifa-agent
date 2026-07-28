# Haifa Agent SDK

面向上层 Agent 产品的纯 Java 高层装配与应用边界。SDK 通过可信 `ProductProfile` 和类型化
`ProductContribution` 确定性解析产品能力，构建唯一 `AgentRuntime`，并提供产品中立的
Conversation Session 服务。

SDK 不替代 Core/Runtime 状态机，不包含 Spring、SQLite、MCP SDK 或具体模型 Provider，也不会
扫描 Classpath 自动导入能力。具体实现仍由对应 Integration/Application 模块提供，并在进程启动时
显式注册。

## 成功路径

```java
try (HaifaAgent agent = HaifaAgents.builder()
        .product(profile)
        .contribute(model)
        .contribute(persistence)
        .contribute(conversation)
        .build()) {
    ConversationRecord started = agent.conversations()
            .start(new StartConversationCommand("start-1", "New chat", "Hello"));
    AgentRunResult result = agent.runs()
            .await(started.activeRunId().orElseThrow());
}
```

`start`/`submit` 同步完成命令接收和 Run 创建，Run 本身异步执行；`await` 委托同一个 Runtime，
不复制 Run 状态。`HaifaAgent` 拥有本次装配已成功初始化的 Contribution 和本地 Scheduler：
关闭时先停止调度，再按能力确定性初始化顺序逆序关闭 Contribution；重复关闭无副作用。构建中途失败
只释放已经成功初始化的资源。

## Product Profile 与装配

- `ProductProfile` 冻结产品 ID/版本、Definition/Profile 引用、预算、限制、指令、Capability
  Requirement 及 Tool/Skill/Extension allowlist，并校验 canonical SHA-256 digest。
- 每个 Capability 使用 `NONE`、`OPTIONAL` 或 `REQUIRED`；未声明能力等价于 `NONE`。
- Contribution 必须声明稳定坐标、能力 ID、配置 digest、生产适用级别和安全摘要。解析拒绝重复坐标、
  多个兼容实现、allowlist 外实现、生产场景中的 Test-only Provider，以及 `NONE` 能力泄漏。
- Contribution 注册顺序不影响选择和 assembly digest。装配结果作为 Runtime
  `ResolvedCapabilities` 的 `product.profile`、`product.assembly` 及精确 Contribution binding
  写入既有配置快照，因此每个 Run 冻结产品语义。
- Tool 和 Skill 只有在 Profile 明确允许且冻结 Catalog 中存在时才进入 Runtime。MCP 先由 Integration
  完成连接、发现、schema/risk 映射和逐项 allowlist，再作为统一 Tool Catalog 的一部分注入；SDK
  不提供绕过 Tool Pipeline 的 MCP 执行通道。

## Conversation 公共边界

一个 `ConversationSessionId` 直接使用一个 Core `AgentSessionId`，一个会话可包含多个 Run，但最多
一个活动 Run。当前 API 提供：

- `start`、`submit`、`rename`、`archive`、`unarchive`；
- `find`、可信 Caller 范围内的稳定 Cursor 列表/搜索；
- 只返回用户可见 User/Assistant 内容的 Turn Cursor 分页；
- 写命令的 caller-scoped idempotency、request digest、expected revision 与单活动 Run 冲突；
- Runtime 已创建 Run、投影尚未完成，以及活动 Run 已终态时的查询期恢复。

删除、回收站、Tree/Fork/Clone、Follow-up Queue 和 Retention 不属于该公共边界。SQLite 实现位于
`haifa-agent-store-sqlite`，SDK 自身不依赖 SQLite；`InMemory` 实现只用于开发和确定性测试。

## 边界

- 公共 API 不暴露 `RuntimeCoreBuilder`、Runtime Core 内部 bootstrap 类型、SQLite/MyBatis、
  Spring、Provider Client、`Path`、Connection 或 Credential 明文。
- Caller 的 Tenant/Principal 来自可信 `SdkCallerProvider`，不从 Conversation 命令正文接收。
- 诊断只包含逻辑产品/能力/Contribution 标识和 digest，不包含 Prompt、Memory、Tool 正文、
  Provider 原始配置或绝对路径。
- SDK 不依赖任何产品 Application。Coding、Personal、Document 等上层产品用各自
  `ProductProfile` 选择不同模块实现，但都复用同一 Runtime 状态机和 SDK 装配器。

## 政策、扩展与错误

- `ProductPolicies` 将 Memory 人工审查与查询边界、Artifact 配额/Media Type/本地容量门禁及
  Execution 主机/网络/并发/超时政策冻结进 Profile canonical digest；本阶段不允许关闭
  Memory Candidate 人工审查。
- Model、Tool、Skill、MCP Tool binding、Context、Memory、Artifact、Policy、Approval、
  Credential 和 Execution/Sandbox 均通过显式 typed Contribution 注册。MCP alias 还必须同时
  出现在 Profile allowlist 与统一 Tool Catalog 中，不存在第二条 MCP 执行通道。
- `HaifaAgentException` 及 `ConversationException` 对外只暴露安全的 `code`、`operation` 和
  `correlation`。Conversation Adapter、SQLite/Runtime 底层异常和输入正文不会进入公共错误消息。
- `HaifaAgent.memories()` 暴露受 Product Profile、可信 `SdkCaller` 与权限约束的产品级
  propose/revise/approve/reject/invalidate/list API；调用命令不能注入 Tenant、Principal 或 Reviewer。
- `HaifaAgent.memory()` 与 `HaifaAgent.artifacts()` 只在 Profile 选中了对应 typed Contribution
  时返回应用服务；Memory 的生产 SQLite Provider 已由 Phase 2 提供，Artifact 生产 Provider 仍延期。

当前开发范围由 `docs/20-agent-sdk-product-session-memory-artifact-foundation.md` 定义。
