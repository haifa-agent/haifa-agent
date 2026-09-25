# Execution 与 Sandbox 边界

Haifa Agent 可以执行宿主进程，但当前实现有意**不宣称自己是操作系统级 Security Sandbox**。

## 当前实现

Execution Broker 通过 Sandbox SPI 使用 host-guarded Provider 作为当前本地实现。

它提供的是应用层 Process Governance，例如：

- 受控 Process Creation；
- 显式 Working Directory；
- Bounded Output；
- Timeout 与 Cancellation；
- Process Tree Cleanup；
- 受控 Environment Handling；
- MCP stdio 等能力使用的 Managed Process。

## 当前不提供什么

host-guarded Provider 当前不承诺：

- Linux namespace isolation；
- macOS Seatbelt isolation；
- Windows AppContainer isolation；
- Container isolation；
- Network isolation；
- CPU / Memory cgroup isolation；
- Hostile multi-tenant code containment。

Working Directory 校验也不等价于 Kernel-enforced Filesystem Isolation。

## Trust Model

Host Execution 适用于受信任、本地产品场景，前提是操作者明确知道命令会以当前 Host Account 权限执行。

如果要执行敌对第三方代码，或者面对强 Multi-tenant Isolation 场景，应把整个 Haifa Agent 进程放进外部安全边界，例如 VM、Hardened Container 或其它专门针对该 Threat Model 的环境。

## Approval 是另一层边界

Policy / Approval 可以要求操作者批准一个精确 Execution Request。

Approval 解决的是“Haifa 是否应该执行这次动作”，它不会把 Host Process 变成 Kernel Sandbox。

## Coding Agent

Coding Agent 通过 Authorized Workspace Directory 与统一 Execution Path 执行 Shell、git、gh、Build Tool 和客户脚本。

Java 层不会再为每一种命令复制一套 Command-specific Permission DSL。
