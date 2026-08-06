# Haifa Agent Host Guarded Sandbox

On Windows, scratch-root hardening first verifies an already exact owner-only ACL and avoids rewriting it. This
keeps repeated startup and execution idempotent in environments that permit verification but reject redundant ACL
mutation. Dispatch is reported only after `ProcessBuilder.start()` succeeds.

首个本地主机 Provider，支持两种执行形式：DIRECT 继续执行显式白名单 argv；SHELL 要求 Profile 明确允许，并把完整命令文本交给可信配置的 Shell。macOS/Linux 默认优先 `/bin/bash -lc`，不可用时回退 `/bin/sh -c`；Windows 默认使用非交互 PowerShell，本地配置也可指定绝对 Git Bash/PowerShell 路径。模型不能选择 Shell 或宿主路径。

Windows PowerShell 包装器对命令不存在、PowerShell 错误和原生命令非零退出统一 fail closed；失败输出
不能与零退出状态共存，复合命令也保留其中的原生命令失败状态。

Provider 约束 Workspace cwd、允许继承的非 secret 环境名称、超时、有界 stdout/stderr 和进程树。它并发排空输出、关闭 stdin、保留截断尾部，并在 timeout/cancel/close 时终止子进程树。它会诚实拒绝无法保证的只读挂载与网络关闭策略，不承诺 CPU、内存、网络或文件系统挂载强隔离。

Host Guarded 在 Coding Agent 三端默认的可信本地开发模式中，从装配提供的私有、Workspace/用户目录之外 Scratch Root
创建 owner-only 会话目录，再解析 `TMPDIR/TMP/TEMP` 与逻辑子目录绑定。创建前校验 symlink、重叠和
可写性；required Scratch 不满足即 fail closed。同步和 Managed Process 都在进程收敛后清理目录，并
通过状态字段报告清理失败，绝不把宿主物理路径投影给模型。

本模块还提供有预算的 `EPHEMERAL_COPY` 和 Git Worktree `COPY_ON_WRITE` Provider。释放操作必须校验 Provider 所有权；脏 Worktree 需要显式确认丢弃。

`SandboxManagedProcess` 是 Host Provider 的受控长驻进程实现：只接受 DIRECT argv、白名单命令和环境名，限制并发进程、stdin/stdout/stderr、运行时间与输出大小，并在 cancel/close 时终止进程树。`ProcessBuilder` 只存在于本 Host 边界；MCP integration 不直接访问它。本 Provider 仍不宣称具备网络、CPU 或文件系统挂载强隔离。

Host Profile 必须精确绑定 `host-guarded` 与当前受信 Shell 配置摘要。Provider 在打开 Workspace 前执行
统一预检；网络 `DENY` 返回 `NETWORK_POLICY_UNENFORCEABLE`，只读或要求文件隔离的 Profile 也会
fail closed。该摘要只用于冻结配置身份，不暴露 Shell 路径或扩大 Host 保证。

Host Guarded 是 Coding Agent 在 macOS、Linux、Windows 上的默认可信本地基线，不是强隔离 Provider，
也不是运行时失败后的隐式回退。它保留命令产生的真实路径，允许普通本地网络及同一命令生命周期内的
临时 Server。需要 OS 原生文件与网络边界的一次性命令时，macOS/Linux 可显式选择独立
`local-native` Provider；Windows 当前没有同等级能力。Host 的 Managed Process 能力不会因此扩展到
Local Native，Coding 产品也仍不开放长期 Server、后台任务或 PTY。
