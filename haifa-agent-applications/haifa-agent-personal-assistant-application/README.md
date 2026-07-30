# Haifa Personal Assistant Application

Run streaming use case 合并两条明确分离的来源：durable Run Event Feed 提供状态、Tool、Interaction 和
Activity；`subscribeOutput` 提供当前进程活动 Run 的 transient Assistant Delta/lifecycle。两者使用独立
sequence，订阅统一可关闭。进程重启后不恢复未完成 Delta；终态正文从 Conversation Turns 的权威
`session_message` 查询。

Personal Assistant 的纯 Java 产品应用层。它只通过 Phase 20 SDK、Conversation Service 和公共
Runtime 视图实现用例，不依赖 Spring、SQLite 实现、HTTP DTO 或 Controller。

本模块负责：

- Conversation start/list/search/get/turns/submit/rename/archive/unarchive；
- Run 查询、取消、最终结果、权威 Usage 与安全 Activity；
- Interaction 查询与响应；
- Memory Candidate review 和 Memory invalidate；
- Personal Product Profile；
- 一个确定性产品 Tool、版本化内置 Skill、可信只读本地 Skill Source；
- 从公共 `haifa-agent-web` 模块显式装配 Aliyun IQS `web.search` / `web.fetch` 和短生命周期凭据；
- 显式本地 MCP connect/discover/allowlist；
- Tool、Skill、MCP 统一冻结到一个 Tool Catalog，并进入同一 Runtime Tool Pipeline。

Personal 在产品装配层对冻结目录中精确选中的 `web.search` / `web.fetch` coordinate 生成
request-bound `ALLOW` Decision，因此公共 Web Search/Fetch 默认不创建 Approval Interaction。
启动时会复核 Tool 名称、完整 coordinate、Provider binding、`POLICY` 声明、Medium 风险、
幂等性、Remote Provider、网络 Host 约束和 Side Effect 集；任一事实漂移都会 fail closed。
其它 Tool 完整委托给 Runtime 原有 Policy，Execution 和高风险业务 Tool 的审批行为不变。

内置 Skill 使用 `STRICT` parser。显式配置的可信只读导入目录使用 `COMPATIBLE` parser，以兼容
Hermes 等外部 `SKILL.md` 的扩展 front matter；未知或嵌套 metadata 不获得执行权限。Personal 导入
边界仍限制 128 个文件、8 层目录、2 MiB 包大小、2000 行指令和 20000 估算 Token，脚本资源只索引为
待审内容，不直接执行。

## Phase 3 command and script execution

Personal Assistant follows the shared cross-platform mode contract: `COMMAND` omits `language` and `args` and uses
the trusted host default shell, while `SCRIPT` requires a configured `language`. The bundled execution Skill states
the same rule so remote models can construct a valid exact-approval request on Windows, macOS, and Linux.

Execution approval prompts are bounded to the Runtime public-view limit. They always retain mode, language, purpose,
bounded arguments, timeout, invocation digest, and risk metadata; script or command content is shown in full when it
fits and otherwise as a marked preview with the original character count. Authorization continues to bind the complete
arguments digest and frozen Tool target rather than the display prompt.

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

## Trusted finance Skill reference

An optional product-owned trust manifest can promote exact reviewed external Skill packages and expose fixed
business Tools for market data, workbook recalculation, and DCF validation. Their Schemas accept only bounded
business inputs; workbook paths must be logical `.xlsx` Workspace paths and are physically checked before
Broker dispatch. The market-data Tool requires explicit frozen hosts, while workbook Tools reject network hosts.

This reference vertical uses the shared package/script grant, Runtime policy, and fixed-script execution
facility. Removing it does not change public Trust, Policy, Runtime, or Execution code. A missing manifest keeps
the feature disabled; invalid, unknown, duplicate, drifted, expired, or revoked entries fail closed.
