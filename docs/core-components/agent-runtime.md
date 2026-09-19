# Agent Runtime

Runtime 是纯 Java 的执行内核，负责协调 Run、Context 构建、冻结 Model 调用、Tool 执行、Interaction、Persistence 与 Completion。

## Runtime 的主要职责

一个活动 Run 大致按下面的循环推进：

~~~text
control / budget checks
        ↓
构建 Context IR
        ↓
调用冻结的 Model Binding
        ↓
归一化 Model Response
        ↓
final answer | Tool call | interaction / pause
        ↓
持久化权威事实
        ↓
继续执行或收敛到终态
~~~

Runtime 不再维护第二套产品化 Planner 或 Progress State Machine。

普通 Tool 失败如何理解、是否重试、是否换方法、是否请求帮助或结束，由主 Model 根据事实判断；Runtime 负责执行硬性的 Safety、Lifecycle 与 Correctness 边界。

## Frozen Execution

Run 创建时会冻结后续执行所需的 Model / Runtime Configuration。

Provider Adapter 通过精确的 Adapter identity / version 与 Model Snapshot 解析。Runtime 不会把一个已经创建的 Run 静默切换到更新的 Model Binding。

## Tool Execution

Tool Call 会依次经过 Schema Validation、Policy / Approval、Journal / Unknown Outcome 防护、Credential、Provider Dispatch 与 Result Persistence。

有 Side Effect 的 Tool 一旦结果变成 Unknown，不会被 Runtime 盲目自动重放。

## Context 与 Compaction

Runtime 构建结构化 Context IR，而不是不断拼接一份共享、可变的 Prompt 字符串。

Session 历史仍是权威事实；Semantic Compaction 可以生成有界 Conversation Summary，同时保留原始 Source Message。

## Completion

Runtime 负责 Lifecycle 收敛与资源硬限制。

产品可以增加 Completion Policy 来要求结构化证据，但普通任务策略仍由 Model 负责，而不是在 Runtime 内再建立一层“第二个 Agent”。

## Output 与 Durable Event

实时 Assistant Text Delta 属于进程内的 Transient Output。

Durable Runtime Event 只记录有界、Provider-neutral 的运行事实，并明确不保存完整 Prompt、Model Reasoning、Credential 或 Provider 原始响应。
