# Changelog

## 0.1.0-SNAPSHOT

- 新增端到端模型 SSE 输出、稳定 Runtime output cursor/replay/listener，并保持同步 `AgentChatModel` 兼容。
- DeepSeek 默认启用 thinking/high；reasoning 通过受保护 continuation 与 Checkpoint 引用完成 Tool Call 续接，公共输出不包含推理原文。
- 新增阿里云百炼与火山方舟 OpenAI Chat dialect、受治理 Provider factory/profile；方舟显式区分 Model ID 与 Endpoint ID。
- 初始化 Maven 多模块工程、BOM、基础领域模型、Runtime API、架构约束和 CI 基线。
- 新增纯 Java Credential API/Core 与 Tool API/Core，提供 AES-GCM Secret Store、scope 解析、短生命周期 Lease、内容寻址 Tool Catalog、Draft 2020-12 Schema 校验和 Provider 路由。
- Runtime 配置快照改为冻结精确 `FrozenToolBinding`，模型规格由冻结定义派生，并将 Tool 审批改为释放 Worker、Checkpoint 后由新 Attempt 恢复的异步协议。
- Project Application 的 14 个 File/Git/Execution Tool 迁入唯一 Tool Catalog/Provider，同时保留 Workspace、capability 与 ExecutionBroker 边界。
- 新增固定协议 `2025-11-25` 的 MCP Client Integration，使用 MCP Java SDK 2.0.0 支持 Streamable HTTP 与 ExecutionBroker-backed stdio，并将远端 Tool 通过内容寻址 binding 接入唯一 Runtime Tool Pipeline。
- 修复模型工具名的 OpenAI-compatible 协议兼容性：内部点号身份保持不变，模型披露 Alias 改用 `file_read`、`git_status` 等 1-64 位安全名称，并恢复首个模型集成约定的非 strict 工具 Schema 默认值。
- 修复 Tool Result 下一轮模型消息只包含摘要的问题：Runtime 从权威 ToolCall 重建结构化结果与裁剪状态，OpenAI-compatible Adapter 将其编码为关联 Tool Message 内容。
- CLI 新增受控的 Streamable HTTP MCP Server 配置、启动期工具发现与 allowlist/profile 审查，并把已审核远端工具接入现有 Catalog、Runtime Policy 和结构化 Tool Result 链路。
- 新增通用 `execution.run` / `execution_run` Shell Tool：明确区分 DIRECT argv 与 SHELL 文本，由可信 Host 配置选择 Bash/PowerShell，经唯一 ExecutionBroker 执行并关联 FileChangeSet。
- CLI 默认启用 ask 审批的本地 Shell 能力，支持 auto/deny disclosure、实时有界脱敏输出、Output Ref、timeout/Runtime/Ctrl+C 取消和受限环境名称继承；具体 CLI 命令均走同一实现，不含逐命令适配。
- 新增纯 Java Skill API/Core/Base，兼容 `SKILL.md`，提供 SDK/Product/Tenant/User/Project 分层发现、确定性解析、内容寻址冻结、渐进披露、Run 级受控激活、资源读取和两个无外部依赖的基础 Skill。
- Runtime 新增最弱 `SKILL` Context 层与 `skill.load` / `skill.resource.read` Tool；Skill 激活和精确内容引用进入 Checkpoint/Resume，脚本只索引审查而不执行。
- CLI 支持从可信配置装配绝对路径的只读本地用户 Skill 目录，并以显式 `skills.allowed` 控制冻结、摘要披露和激活范围。
- CLI 新增 `--trace summary|detail|jsonl` 与 `--trace-file <path>`，实时输出现有 Runtime 安全 Trace，并按 Provider 区分模型、普通 Tool、MCP 与 Skill 调用；Trace 不包含 Prompt、Tool 原始参数、Credential、reasoning 原文或供应商原始响应。
