# Changelog

## 0.1.0-SNAPSHOT

- 初始化 Maven 多模块工程、BOM、基础领域模型、Runtime API、架构约束和 CI 基线。
- 新增纯 Java Credential API/Core 与 Tool API/Core，提供 AES-GCM Secret Store、scope 解析、短生命周期 Lease、内容寻址 Tool Catalog、Draft 2020-12 Schema 校验和 Provider 路由。
- Runtime 配置快照改为冻结精确 `FrozenToolBinding`，模型规格由冻结定义派生，并将 Tool 审批改为释放 Worker、Checkpoint 后由新 Attempt 恢复的异步协议。
- Project Application 的 14 个 File/Git/Execution Tool 迁入唯一 Tool Catalog/Provider，同时保留 Workspace、capability 与 ExecutionBroker 边界。
- 新增固定协议 `2025-11-25` 的 MCP Client Integration，使用 MCP Java SDK 2.0.0 支持 Streamable HTTP 与 ExecutionBroker-backed stdio，并将远端 Tool 通过内容寻址 binding 接入唯一 Runtime Tool Pipeline。
