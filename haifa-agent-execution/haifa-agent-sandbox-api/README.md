# Haifa Agent Sandbox API

定义 Sandbox Profile、Provider、Session、挂载能力声明，以及临时副本和 Git Worktree 隔离 Workspace 的 SPI。

能力声明必须反映 Provider 的真实保证；调用方不得把 Host 受控执行等同于容器或虚拟机强隔离。

`SandboxSession` 的一次性执行支持可选 `ExecutionOutputObserver`，同时保持原同步方法兼容。Provider 必须并发排空 stdout/stderr，并在 timeout、cancel 或 close 时尝试收敛整个进程树。
