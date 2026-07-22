# Haifa Agent Host Guarded Sandbox

首个本地主机 Provider，执行显式白名单 argv，并约束 Workspace cwd、环境变量、超时、stdout/stderr 和进程树。它会诚实拒绝无法保证的只读挂载与网络关闭策略，不承诺 CPU、内存、网络或文件系统挂载强隔离。

本模块还提供有预算的 `EPHEMERAL_COPY` 和 Git Worktree `COPY_ON_WRITE` Provider。释放操作必须校验 Provider 所有权；脏 Worktree 需要显式确认丢弃。

`SandboxManagedProcess` 是 Host Provider 的受控长驻进程实现：只接受 DIRECT argv、白名单命令和环境名，限制并发进程、stdin/stdout/stderr、运行时间与输出大小，并在 cancel/close 时终止进程树。`ProcessBuilder` 只存在于本 Host 边界；MCP integration 不直接访问它。本 Provider 仍不宣称具备网络、CPU 或文件系统挂载强隔离。
