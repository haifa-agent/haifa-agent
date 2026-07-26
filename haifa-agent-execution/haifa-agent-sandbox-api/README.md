# Haifa Agent Sandbox API

定义 Sandbox Profile、Provider、Session、挂载能力声明，以及临时副本和 Git Worktree 隔离 Workspace 的 SPI。

能力声明必须反映 Provider 的真实保证；调用方不得把 Host 受控执行等同于容器或虚拟机强隔离。

`SandboxSession` 的一次性执行支持可选 `ExecutionOutputObserver`，同时保持原同步方法兼容。Provider 必须并发排空 stdout/stderr，并在 timeout、cancel 或 close 时尝试收敛整个进程树。

`SandboxProfile` 以稳定 Ref 精确绑定 Provider 与 SHA-256 配置摘要，并分别表达网络策略、最小文件
策略和必需 `SandboxCapabilities`。额外路径只以可信配置引用出现，公共 Profile 不接受宿主绝对路径。
`SandboxProvider.preflight` 在 Dispatch 前返回本配置下的实际能力与 Managed Process 支持状态；绑定、
摘要或能力不匹配时必须使用稳定安全错误拒绝。

当前文件策略只区分 Workspace `READ_ONLY/READ_WRITE`、敏感路径拒读要求和有界额外路径策略引用。
CPU、内存、磁盘、PID、Kernel、Container 与多租户保证没有进入该最小模型。

当前具体实现包括 `host-guarded` 和 `local-native`。后者仅在平台 Adapter 预检成功后兑现文件系统、
进程树和 `NetworkPolicy.DENY` 隔离；公共 API 不暴露 Seatbelt、bubblewrap 或宿主路径。
