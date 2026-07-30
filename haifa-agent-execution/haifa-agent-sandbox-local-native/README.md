# Haifa Agent Local Native Sandbox

`local-native` 是本地一次性命令的具体 Sandbox Provider。公共 Provider 身份不包含 OS 品牌；模块内部
在 macOS 使用 Seatbelt，在 Linux 使用 bubblewrap。Windows 当前明确返回
`SANDBOX_ADAPTER_UNAVAILABLE`，不会回退 `host-guarded`。

本 MVP 只声明经过平台预检的进程树、文件系统和 `NetworkPolicy.DENY` 网络隔离。它不声明 CPU、
内存、磁盘、PID、独立 Kernel、Container、VM 或多租户保证，也不支持 Managed Process、MCP stdio、
PTY、Credential 注入或 Remote。

Provider 只消费冻结的 `SandboxProfile` 和可信 `LocalNativeSandboxConfiguration`。模型不能选择
Adapter、宿主路径、Shell、Mount、环境变量或 Sandbox 参数。额外路径通过有界策略 Ref 解析；敏感路径
或配置冲突 fail closed。

每次执行在受控目录内创建 owner-only Scratch。macOS Seatbelt 将其作为已有 Control Directory
生命周期的一部分，并向子进程设置 `TMPDIR/TMP/TEMP` 及产品声明的逻辑绑定；Linux bubblewrap 在
沙箱内以私有 `/tmp` 提供同一语义并设置相同变量。Provider 在启动前验证 required 目录和绑定，
失败时返回稳定 provision failure；执行结束、超时或取消后清理，Trace 仅报告 Spec digest 和能力。

macOS Seatbelt 策略只对根目录本身开放 dyld 启动所需的目录读取，并精确开放 `/dev/null`；这些规则不
允许读取 Workspace、受信额外路径和系统只读路径之外的文件，也不改变冻结的网络策略。策略中的受信
路径会解析为物理路径，避免 `/var` 等系统别名导致 OS 规则与实际工作目录不一致。
