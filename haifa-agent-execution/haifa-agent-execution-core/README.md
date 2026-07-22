# Haifa Agent Execution Core

实现 `ExecutionBroker`、内存 Journal/输出存储、执行前后 Workspace Manifest 及 `FileChangeSet` 对账。

Broker 负责 capability、policy、profile、环境租约、Sandbox 生命周期、输出脱敏与审计编排，但不复制 Agent Run 状态机，也不依赖具体 Sandbox Provider。一次性执行的展示 observer 经过有界异步分发，不阻塞进程管道；环境值脱敏支持 secret 跨 chunk，observer 异常不影响进程收尾和 Execution Journal。

stdout/stderr 在各自执行预算内写入 `ExecutionOutputStore`，超过 inline 阈值后返回 `AssetRef`。Project Tool 只向模型返回按观察顺序合并的有界尾部，完整的分通道输出仍由 Execution Result 引用。

长驻会话与一次性执行共享相同的可信上下文、授权、环境解析、Sandbox Profile、输出预算、脱敏、Manifest 和审计流程。会话关闭、取消或异常退出时，Broker 先收敛底层进程与输出，再释放环境租约并完成审计记录。
