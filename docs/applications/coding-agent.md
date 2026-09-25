# Coding Agent

Haifa Coding Agent 是构建在共享 Runtime、Tool、Project、Execution 与 Persistence 基础之上的本地软件工程产品。

它的产品哲学刻意保持 Model-first：Model 负责规划，并解释普通 Command Result；Runtime 负责执行确定性的 Safety、Lifecycle、Budget 与 Execution 边界。

## 当前 Product Surface

当前产品包括：

- 持久化 Coding Session；
- 基于 tui4j / JLine 的 Terminal Client；
- Authorized Workspace Directory；
- 文件读取与修改 Tool；
- 受控 Host Command Execution；
- 通过通用 Execution Path 直接调用系统 git / gh；
- Tool、Skill、可选 MCP 与可选 Web Capability；
- 基于可信 Product Profile 的 Model Selection；
- 配置后可使用 SQLite 持久化 Session / Runtime；
- 对 Policy 判定为 ASK 的动作进行显式 Human Approval。

Terminal / UI 只依赖 CodingSessionClient 产品契约，而不会直接访问 Runtime Store。

## Workspace Authorization

Coding Agent 不假设自己拥有任意 Filesystem 权限。

Host Directory 必须显式 Attach 成 Authorized Directory，并具有 READ 或 DEVELOP Access。

File Mutation 与 Model-triggered Execution 会在实际执行时重新检查当前 Authorization。

物理 Path / Fingerprint 属于 Host-side Security State，而不是 Model 可以提供的 Authorization Token。

## Command 与 git

Model 使用通用 execution_run Tool 执行 Build、Test、Shell、git、gh 与客户脚本。

Haifa 不再在 Java 中维护一套试图理解所有 git / gh 子命令的大型 Grammar。

系统仍保留一条很窄的 fail-closed Credential Protection Boundary，用来阻止已知会泄露 Credential 或修改 Authentication State 的命令流经 Model Execution Path。

Process 非零 Exit Code 只是 Command Result 的一部分，不会自动等同于 Runtime Failure。

## Completion

Coding Agent 不再维护第二套 Delivery State Machine，去判断开放式 Coding Task 是否“真正完成”。

Model 根据用户任务、仓库指令、Tool Result、Exit Code 与 Bounded Output 自己判断下一步。

Runtime 仍会阻止确定性的 False Success，例如：

- 未解决 Tool Call；
- Pending Interaction；
- Unknown Side Effect；
- Cancellation；
- Timeout；
- Resource Exhaustion；
- Final Output Protocol Violation。

## Execution Trust

Command 通过 host-guarded Execution Provider 运行。

详见 [Execution 与 Sandbox 边界](../advanced/execution-and-sandbox.md)。

对于本地开发，这是 Controlled Trusted-host Model，而不是 Hostile-code Containment。

## 更详细的产品文档

精确配置与 Terminal 行为变化通常比本概览更快，请以对应模块 README 为准：

- [Coding Agent Module](../../haifa-agent-applications/haifa-agent-coding-agent/README.md)
- [Coding Terminal](../../haifa-agent-applications/haifa-agent-coding-terminal/README.md)
- [CLI](../../haifa-agent-applications/haifa-agent-cli/README.md)
