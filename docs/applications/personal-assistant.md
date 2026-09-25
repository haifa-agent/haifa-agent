# Personal Assistant

Haifa Personal Assistant 是构建在共享 Runtime / SDK 之上的本地 Assistant Application，同时拥有产品自己的 Conversation、Mission、Web 与 Persistence 层。

它是一个具体 Application，而不是通用 Production Agent Server。

## 组成

当前应用主要分为：

- Pure Java Personal Assistant Application Layer；
- Loopback-only Spring Boot WebFlux Server；
- 独立 React Web Application；
- SQLite Product / Runtime Persistence；
- 可选 Tool、Skill、MCP、Web Search / Fetch、Memory 与 Host Execution；
- 本地只读 Diagnostics / Admin Surface。

Backend 与 Web Frontend 是独立 Deployment Unit。

## 本地 Trust Boundary

Server 面向 Trusted Local Machine，并默认只绑定 Loopback。

这不能被解释成 Hardened Internet-facing Multi-tenant Server。

Host Execution 默认会 fail closed，只有本地 Deployment 明确确认 Trusted-host Boundary 后才启用相关能力。

## Conversation 与 Model

Model Provider 与 Model Binding 由可信 Server 配置。

Browser Client 只接收安全的 Model / Display Metadata 与 Preference，不接收原始 Provider Endpoint、Credential、Adapter Internal 或 Frozen Snapshot Digest。

Credential 始终留在 Server-side。

## Mission 与 Deep Research

Personal Mission 是构建在公共 Runtime 之上的 Application-owned Durable Aggregate。

Deep Research 拥有自己的 Planning / Task / Synthesis 语义，同时复用公共 Model、Tool、Skill、Artifact、Runtime 与 Persistence Mechanism。

Mission State 不会仅仅因为需要持久化，就被下沉到 Core / Runtime。

## Media

当前产品根据所选 Model Capability 与 Server Upload Policy 支持受治理的 Image / Audio Input。

上传媒体由 Product-owned Bounded Store 保存，Conversation Record 只保存 Opaque Reference / Metadata，而不会直接嵌入大块 Base64 Payload。

## MCP 与 Web

MCP 与 Web Search / Fetch 都是可选能力，并且需要显式配置。

MCP 不会隐式启动或扫描任意全局 MCP Server；Web Credential 也保持在 Server-side。

## Recovery Semantics

正常持久化 Interaction / Intentional Pause 可以按照 Runtime 规则继续。

执行中的 owner 如果异常丢失，不会透明 Resume 并假装什么都没发生；Runtime 会采用 [持久化与恢复](../core-components/persistence-and-recovery.md) 中描述的 Safe Interrupted-execution Semantics。

## 更详细的产品文档

- [Application](../../haifa-agent-applications/haifa-agent-personal-assistant-application/README.md)
- [Server](../../haifa-agent-applications/haifa-agent-personal-assistant-server/README.md)
- [Web](../../haifa-agent-applications/haifa-agent-personal-assistant-web/README.md)
