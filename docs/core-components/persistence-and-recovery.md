# 持久化与恢复

Haifa Agent 明确区分 Durable Fact 与 Transient Process State。

目标不是序列化整个 Runtime，而是只持久化产品明确恢复承诺所需要的权威事实。

## 当前 Durable Reference

SQLite 是当前 Single-node Durable Reference Implementation。

它提供版本化 Codec / Migration，以及 Runtime Fact 与部分 Product Capability 所需的 Persistence Port。

JSONL 是安全 Transcript Projection，可用于检查或导出，但不是权威 Recovery Source。

## Intentional Continuation

正常 Pause 与阻塞 Interaction，只要权威持久化事实仍存在且有效，就可以跨进程重启继续。

Checkpoint 有意保持很小，不复制 Tool Result、Memory、Skill Content、Model Continuation 或每一个外部 Capability State。

## Interrupted Execution

执行 owner 异常丢失时，不会被当成 Transparent Failover。

Recovery 会安全收敛 Abandoned Execution Attempt。

如果有 Side Effect 的 Tool Outcome Unknown，Run 会按 Unknown Outcome 失败，而不是再次 Dispatch Tool 并假设 Replay 一定安全。

## 权威事实

典型 Authoritative Facts 包括：

- Run、Session、Step、Attempt State；
- ToolCall Result State；
- Pending Interaction State；
- Durable Runtime Event；
- 解释 Run 所需要的 Frozen Configuration reference / digest。

Transient Assistant Text Streaming 与进程内 Diagnostics 都不是 Recovery Fact。

## Product-owned Persistence

产品可以为 Memory、Artifact、Mission 或其它产品事实增加持久化，而不必把每个 Capability 都塞进统一 Snapshot Protocol。

这是有意的架构边界：Persistence 跟随具体 Recovery Promise，而不是跟随“Enterprise”这样的抽象形容词。
