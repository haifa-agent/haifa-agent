# Haifa Agent Execution Core

实现 `ExecutionBroker`、内存 Journal/输出存储、执行前后 Workspace Manifest 及 `FileChangeSet` 对账。

Broker 负责 capability、policy、profile、环境租约、Sandbox 生命周期、输出脱敏与审计编排，但不复制 Agent Run 状态机，也不依赖具体 Sandbox Provider。
