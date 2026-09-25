# Runtime 与模块边界

本文只记录对 Contributor 与 Embedder 最有价值的公共 Architecture Boundary，不复制内部文档中的全部设计历史。

## Runtime 是执行内核

Runtime 拥有：

- Run orchestration 与 Lifecycle coordination；
- Context construction；
- Frozen Model invocation；
- Tool Pipeline coordination；
- Interaction / Approval execution state；
- Hard budget、Cancellation 与 Completion convergence；
- Persistence Port 与 Durable Runtime Event。

Runtime 不拥有：

- Product UI / Navigation；
- Coding Agent workflow heuristic；
- Personal Assistant Mission Domain；
- Provider-specific HTTP payload；
- Spring Lifecycle；
- 通用 Enterprise Authorization Model。

## Core 拥有 Lifecycle 合法性

AgentRun 的合法 State Transition 由 Core Domain Behavior 唯一决定。

Runtime 调用这些行为，而不是复制一份第二 State Machine。

## ProductProfile 是 Composition，不是 Capability Discovery

ProductProfile 冻结可信 Product identity、Agent Definition reference、instructions、default Run Profile、budget / limits 与 Tool / Skill allowlist。

当前 SDK 偏向 Explicit Typed Composition，不再使用“根据 suitability 自动解析所有 Capability”的通用 Framework。

## Model Boundary

Runtime 只依赖 Provider-neutral Model API。

Run 持久化精确 Model Snapshot / Adapter identity，使执行语义可确定。

Provider Integration 拥有具体 Protocol、Authentication Mapping、Request / Stream Parsing 与 Provider-specific Compatibility。

## Tool Boundary

Tool Core 拥有 Definition、Catalog 与 Schema Validation Primitive。

Runtime 拥有执行顺序与 Safety Gate。

SDK JavaTool 只是进入同一 Tool Core 路径的 Convenience Adapter；MCP 也是把 Tool 导入这条路径，而不是建立另一套 Runtime。

## Project 与 Host Boundary

逻辑 Project / Workspace 概念与物理 Host Access 分离。

Host-side Module 拥有 Filesystem / Process Integration。

这样可以避免 Path / Process 细节进入 Public Core Domain，同时让 Trust Boundary 更清楚。

## Persistence Boundary

Runtime 依赖 Persistence Port，而不是 SQLite / MyBatis。

SQLite 是 Durable Single-node Reference Adapter，JSONL 是 Projection。

Application-owned Durable State（例如 Personal Mission）即使复用 SQLite Infrastructure，也仍然属于 Application。

## Spring Boundary

Spring 从 Spring Adapter / Starter 开始引入。

Core、Runtime、SDK 与 Capability API 保持 Pure Java。

## Public Docs 与内部 Design History

已删除的实验和 Superseded Design 不属于 Public Baseline。

尤其，当前代码没有把 Graph / Workflow Orchestration 暴露为受支持 Runtime Capability。
