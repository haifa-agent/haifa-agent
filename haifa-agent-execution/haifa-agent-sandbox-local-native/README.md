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
