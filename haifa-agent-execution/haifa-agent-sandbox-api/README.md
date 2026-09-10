# Haifa Agent Sandbox API

> 沙箱裁剪声明：平台已放弃 OS namespace / 容器级强隔离，底层统一为受控宿主执行。
> 详见 `docs/34-sandbox-simplification-and-host-execution-design.md`。

定义 Sandbox Profile、Provider、Session、宿主进程能力声明，以及 Git Worktree 隔离 Workspace 的 SPI。

能力声明必须反映 Provider 的真实保证；调用方不得把 Host 受控执行等同于容器或虚拟机强隔离。

`SandboxSession` 的一次性执行支持可选 `ExecutionOutputObserver`，同时保持原同步方法兼容。Provider 必须并发排空 stdout/stderr，并在 timeout、cancel 或 close 时尝试收敛整个进程树。

`SandboxProfile` 以稳定 Ref 精确绑定 Provider 与 SHA-256 配置摘要，并只表达应用层治理：允许的可执行文件、
允许的环境变量名与是否允许 Shell 调用。`SandboxProvider.preflight` 在 Dispatch 前返回本配置下的 Provider
绑定、配置摘要、宿主进程能力声明与 Managed Process 支持状态；绑定或摘要不匹配时必须使用稳定安全错误拒绝。

`SandboxCapabilities` 只声明进程树收割（`processTreeTermination`）。网络断网、文件挂载隔离、CPU、内存、
磁盘、PID、Kernel、Container 与多租户保证都不在该模型内，也不由平台承诺。

当前唯一实现是 `host-guarded`（`haifa-agent-sandbox-host`），基于 Java `ProcessBuilder` 在宿主机上执行，
Windows / Linux / macOS 行为一致。
