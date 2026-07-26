# Haifa Agent Execution

该 Reactor 聚合命令执行、Sandbox SPI、执行协调与首个受控 Host Provider。命令契约明确区分内部可信组件使用的 DIRECT argv 与通用 Tool 使用的完整 SHELL 文本；两者共享同一个 Broker、授权、环境、审计、取消和输出链。API 与 SPI 保持纯 Java；具体 Provider 不反向进入 Kernel、Runtime 或产品模块。

依赖方向：`execution-core -> execution-api + sandbox-api + project`，`sandbox-host -> sandbox-api + project`。只有 `sandbox-host` 的受控实现允许直接使用 Java 进程 API；Project Tool、Runtime、CLI 和 MCP 均通过 `ExecutionBroker`。

Sandbox Profile 现在精确冻结 Provider、Provider 配置摘要、文件策略和必需能力。Execution Core 在取得
环境租约和打开 Session 前完成 Profile、Provider、预检结果和能力匹配；未知、冲突或能力不足均
fail closed，不做 Provider 轮询或 Host 回退。Local Native 具体 Provider 尚未在本阶段实现。
