# Capabilities

Haifa Agent 刻意采用组合式设计。产品应只装配当前场景需要的 Capability，而不是默认开启一套“大而全”的 Platform Profile。

## Model

Provider-neutral Model API 与具体 Provider Integration 分离。

Run 使用冻结的 Model Snapshot 与精确 Adapter coordinate。

## Tool

Tool 是带类型与 Schema Validation 的执行单元。

Java Tool、导入的 MCP Tool、Web Tool 与 Execution Tool 最终都进入同一条 Runtime Tool Pipeline。

## Skill

Skill 是具有冻结 identity 与 Progressive Disclosure 的受控 instruction / resource package。

激活 Skill 不会自动授予 Tool、Network、Filesystem 或 Credential 权限。

## MCP

MCP 是外部 Tool Provider 的 Integration 边界。

Haifa MCP Client 对外部定义进行发现、审查与映射，再把允许的条目导入本地 Tool Catalog。

系统不存在第二套 MCP-specific Tool Runtime。

## Memory

Memory 是可选能力。

当前共享 Memory Capability 支持受治理的 Candidate / Formal Memory，也可以使用 SQLite 持久化。是否启用由产品决定。

## Credential

Credential 通过间接 Reference 表达，并在可信执行边界解析 Secret。

Secret 值不会进入 ProductProfile、Run Prompt 或 Public Diagnostics。

## Policy 与 Approval

PolicyDecision 是瞬态事实。

当前 Decision 优先级为 DENY > ASK > ALLOW。

ASK 通过 Runtime Interaction 绑定到精确 Target；它不是可重复使用的 IAM Grant。

## Project / Workspace

Project API 表达逻辑 Product / Workspace identity。

物理 Host Filesystem 访问收敛到 Host-side Adapter。

Coding Agent 使用显式授权目录，而不是默认拥有任意文件系统访问权限。

## Execution

Execution 通过 Execution Broker 与 Host Provider 启动受控宿主进程。

它提供的是 Process Governance，而不是 OS Security Sandbox。详见 [Execution 与 Sandbox 边界](../advanced/execution-and-sandbox.md)。

## Persistence

SQLite 是当前 Durable Single-node Reference Implementation，用于 Runtime 与部分产品 Capability。

JSONL 是安全 Transcript Projection，而不是权威 Recovery Store。
