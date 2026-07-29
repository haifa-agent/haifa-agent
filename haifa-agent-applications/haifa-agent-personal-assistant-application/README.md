# Haifa Personal Assistant Application

Personal Assistant 的纯 Java 产品应用层。它只通过 Phase 20 SDK、Conversation Service 和公共
Runtime 视图实现用例，不依赖 Spring、SQLite 实现、HTTP DTO 或 Controller。

本模块负责：

- Conversation start/list/search/get/turns/submit/rename/archive/unarchive；
- Run 查询、取消、最终结果、权威 Usage 与安全 Activity；
- Interaction 查询与响应；
- Memory Candidate review 和 Memory invalidate；
- Personal Product Profile；
- 一个确定性产品 Tool、版本化内置 Skill、可信只读本地 Skill Source；
- 显式本地 MCP connect/discover/allowlist；
- Tool、Skill、MCP 统一冻结到一个 Tool Catalog，并进入同一 Runtime Tool Pipeline。

## Phase 3 command and script execution

Personal Profile 通过 SDK 的 `ShellPlatformContribution` 接入共享 `execution.run`，产品别名为
`execution_run`。`PersonalExecutionPlatform` 负责产品级 alias、Skill 和审批文案，不复制
Execution Broker、Sandbox 或 Policy。

每次执行都创建 exact approval。审批内容显示 mode、language、purpose、args、timeout、完整正文、
调用摘要、Workspace 边界和 Host 风险；拒绝不会进入 STARTED。内置
`local-script-execution` Skill 只能调用 `execution_run`，不会绕过审批、自动重试副作用执行，或
宣称当前 Host Guarded Provider 提供强隔离。

本模块不负责 HTTP、Spring 装配、SQLite 初始化、Web 页面，也不创建 Personal 专用
Contract、Store、Starter、Tools 或 Skills 子工程。

验证：

```powershell
.\mvnw.cmd -pl :haifa-agent-personal-assistant-application -am test
```
