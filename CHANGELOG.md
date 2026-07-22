# Changelog

## 0.1.0-SNAPSHOT

- 初始化 Maven 多模块工程、BOM、基础领域模型、Runtime API、架构约束和 CI 基线。
- 新增纯 Java Credential API/Core 与 Tool API/Core，提供 AES-GCM Secret Store、scope 解析、短生命周期 Lease、内容寻址 Tool Catalog、Draft 2020-12 Schema 校验和 Provider 路由。
- Runtime 配置快照改为冻结精确 `FrozenToolBinding`，模型规格由冻结定义派生，并将 Tool 审批改为释放 Worker、Checkpoint 后由新 Attempt 恢复的异步协议。
- Project Application 的 14 个 File/Git/Execution Tool 迁入唯一 Tool Catalog/Provider，同时保留 Workspace、capability 与 ExecutionBroker 边界。
- 修复模型工具名的 OpenAI-compatible 协议兼容性：内部点号身份保持不变，模型披露 Alias 改用 `file_read`、`git_status` 等 1-64 位安全名称，并恢复首个模型集成约定的非 strict 工具 Schema 默认值。
- 修复 Tool Result 下一轮模型消息只包含摘要的问题：Runtime 从权威 ToolCall 重建结构化结果与裁剪状态，OpenAI-compatible Adapter 将其编码为关联 Tool Message 内容。
