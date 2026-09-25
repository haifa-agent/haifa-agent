# 架构概览

Haifa Agent 是一个分层 Java 系统：Domain / Runtime 语义保持 Pure Java，Integration 负责适配外部系统，Application 负责装配具体产品。

~~~text
Applications
    |
SDK / Spring adapters
    |
Runtime + Capability APIs
    |
Kernel / Core domain
    |
Host adapters and external integrations
~~~

## 主要层次

### Kernel

Kernel 包含 Common / Core Domain、Runtime API / Core、Context、Project API / Core / Host 与 Artifact 基础能力。

Core Domain 拥有 AgentRun Lifecycle 等权威规则。

### Capabilities

Model、Tool、Skill、Credential、Memory、Policy 是彼此独立的 Capability Family。

它们提供 Pure Java API / Core Implementation，不要求依赖 Spring 或具体产品 Application。

### Execution

Execution API / Core 负责协调 Command / Process Execution。

Host Adapter 拥有物理 Process / Filesystem Interaction。

Sandbox SPI 有意保持窄小；当前本地 Provider 基于 Host Process，并不宣称强 OS Isolation。

### Integrations

Integration 实现 Model Provider、MCP、Web、SQLite、JSONL、HTTP / SSE、Git Evidence 等协议或存储边界。

### SDK

SDK 提供高层 HaifaAgent Facade、Conversation / Run API、Typed Java Tool、Structured Final Output 与显式 Product Composition。

### Spring

Spring Boot 只是 Pure Java SDK 外的一层 Adapter，负责 Configuration、Bean Discovery 与 Lifecycle。

Spring 不拥有 Runtime 语义。

### Applications

Coding Agent 与 Personal Assistant 是具体 Product Assembly。

产品概念应停留在 Application Layer，除非有独立证据证明某个低层 Invariant 稳定且可复用。

## Dependency Rule

高层可以依赖低层，低层不能反向依赖具体产品 Application。

尤其：

- Core 不依赖 Spring、SQLite、Provider SDK 或产品 UI 概念；
- Runtime 不依赖 Coding Agent / Personal Assistant Domain Type；
- Provider Integration 适配 Provider-neutral API；
- Application 是 Composition Root。

## 当前 Non-goals

0.1.1 baseline 不宣称已经提供 Distributed Worker / Control Plane、Graph / Workflow Runtime、Enterprise IAM Product 或强内建 Sandbox。

详见 [Runtime 与模块边界](runtime-and-module-boundaries.md)。
