# 核心概念

Haifa Agent 将面向 Model 的执行循环与面向产品的 Composition 分开。这个区分很重要，因为一次 Model 调用、一次用户 Conversation 与一次可恢复执行并不是同一个概念。

## HaifaAgent

HaifaAgent 是完成装配后由宿主持有的 SDK Facade。它拥有 Runtime 资源，并提供 Conversation 与 Run API。

HaifaAgent 不是某一次请求，也不是某一个 Run。

## Conversation / Session

Conversation 是 SDK 面向用户的多轮容器，底层使用 Core Session identity。

Conversation 层只保存面向产品的轻量展示/索引元数据；Session、Turn、Run 等权威执行事实仍由 Runtime 持有。

新的用户 Turn 通常创建新的 Run；恢复一个有意暂停的执行则继续原 Run。

## AgentRun

AgentRun 表示一次权威执行，并拥有受控 Lifecycle。

Run 创建时会冻结后续执行需要的配置，使 Model Catalog、Product 默认值等之后发生变化时，不会静默改变正在执行或历史 Run 的语义。

公开 SDK 主要暴露 Snapshot / Result，而实际执行机制由 Runtime Core 持有。

## ProductProfile

ProductProfile 是可信宿主提供的产品配置，包括：

- Product identity；
- Agent Definition reference；
- instructions；
- default Run Profile；
- budget / limits；
- Tool / Skill allowlist。

它不是 IAM 系统，也不再承担通用 Capability Resolution Framework 的职责。

## Capabilities

Model、Tool、Skill、Memory、Credential、Policy、Artifact、Project / Workspace、Execution、MCP 与 Persistence 是彼此分离的能力或 Integration。

一个产品只应该装配当前场景真正需要的部分。

## Tool、Skill 与 MCP

- **Tool**：Runtime Tool Pipeline 看到的统一可调用执行单元。
- **MCP**：发现/导入 Tool 的一种方式，不建立第二套 Tool Runtime。
- **Skill**：受控的 instructions / resources 包，可帮助 Model 学会如何完成某类任务，但安装 Skill 不会自动获得 Host、Network、Credential 或 Tool 权限。

## Interaction 与 Approval

当 Policy 返回 ASK 时，Runtime 会创建一个绑定到精确 Action Target 的阻塞 Interaction。

可信 Responder 可以批准或拒绝该 Interaction。Approval 不会自动变成可以重复使用的通用权限 Grant。

## Persistence 与 Recovery

Haifa Agent 只持久化产品明确恢复承诺所需要的权威事实。

它不会尝试 Snapshot 每一个 Capability，也不会把所有外部 Side Effect 都伪装成可以透明重放的操作。

详见 [持久化与恢复](../core-components/persistence-and-recovery.md)。
