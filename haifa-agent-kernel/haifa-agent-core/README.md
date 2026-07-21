# Haifa Agent Core

定义最稳定的 Agent 领域语言，并实现 03 文档第一阶段可实际消费的领域闭环。

- 允许依赖：`haifa-agent-common` 和 JDK；测试范围内允许测试类库。
- 禁止依赖：Spring、Spring AI、Provider、数据库、Sandbox、Product、Admin 和 Contract。
- 公共 API 按 `agent`、`session`、`run`、`message`、`content`、`step`、`tool`、`plan`、`checkpoint`、`error`、`event` 与 `reference` 分包。
- `AgentDefinition` 不可变且显式版本化；Run 同时保存 Definition ID/版本与不可变配置快照引用，保证历史运行可复现。
- `AgentSession` 只组织多轮交互和多次 Run，不固定绑定任何 AgentDefinition，也不内嵌完整消息或 Run 集合。
- `AgentRun` 统一表达根 Run 和子 Run，通过 `rootRunId`、`parentRunId`、`depth` 与 `invocationMode` 建模委派和 Fork/Join。
- Run 使用 `PENDING`、`QUEUED`、`RUNNING`、挂起/等待、`COMPLETING` 及四种终态的完整状态机。状态只能通过命名领域行为改变，终态不可恢复。
- 最终结构化结果为 Core 的 `AgentRunResult`；Message 原生使用不可变 `List<ContentPart>`；Asset 与 Artifact 只以 Ref 进入 Core。
- ID 和时间由调用方生成后传入，Core 不调用随机生成或 `Instant.now()`。
- 集合及 Metadata 均进行防御性复制；聚合不公开 Setter。
