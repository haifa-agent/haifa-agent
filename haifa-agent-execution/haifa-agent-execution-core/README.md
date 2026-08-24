# Haifa Agent Execution Core

## Public Policy integration

`PolicyDecisionExecutionPolicy` 要求 `TrustedExecutionContext.policyDecisionRef` 真实可查，并复核
Tenant、Principal、Run、Action、Execution 请求摘要、Snapshot，以及 `ASK` 的有效满足证据。
它不会创建 Interaction，因此 `execution.run` 的用户可见审批只发生在 Tool/Runtime 层。公共
Decision 不能覆盖 Broker 既有的 Frozen Capability、Workspace、Profile、Provider、Sandbox、
deadline、输出和审计硬边界。

实现 `ExecutionBroker`、内存 Journal/输出存储、可替换的 `WorkspaceChangeObserver` 及 `FileChangeSet`
对账。公共 `LocalIncrementalWorkspaceChangeObserver` 显式绑定一个 `WorkspaceId` 与规范化物理根，首次使用
建立基线，正常窗口只处理 WatchService 候选；macOS 对短窗口内遗漏的事件使用元数据索引补齐候选，仍只对
变化候选计算内容哈希；settle deadline 只在事件持续活跃时触发安全重同步，不把安静轮询期间的 Runner
调度停顿误判为 overflow；真实 overflow 或状态不确定时仅在该 Workspace 内重同步。产品
只提供 Workspace 内逻辑路径的 ignore policy，不扫描 HOME、AppData、XDG 或其它宿主安装目录。进程启动前观察基线失败以稳定错误
`WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE` 明确拒绝，不产生 Execution 记录，也不进入结果未知状态；进程启动后
观察收敛失败映射为 `WORKSPACE_CHANGE_OBSERVER_RESYNC_FAILED`。

Broker 负责 capability、policy、profile、环境租约、Sandbox 生命周期、输出脱敏与审计编排，但不复制 Agent Run 状态机，也不依赖具体 Sandbox Provider。一次性执行的展示 observer 经过有界异步分发，不阻塞进程管道；环境值脱敏支持 secret 跨 chunk，observer 异常不影响进程收尾和 Execution Journal。Provider 只在 `ProcessBuilder.start()` 成功后发出 `onStarted`，上层据此记录真实 DISPATCHED 边界。

Broker 将请求的逻辑 Scratch Spec 原样传给选定 Provider，并把一次性与 Managed Process 的创建、清理
状态带回结果。`execution.run` 的冻结配置摘要和幂等身份包含 Scratch Spec digest；Tool 结构化结果与
Runtime Event 只记录该 digest、能力和状态，不记录物理路径。

stdout/stderr 由 Provider 持续排空，并在固定内存中保留有界首部和尾部；中间省略量写入明确标记，超过
inline 阈值后返回 `AssetRef`。`ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL` 允许普通构建继续完成，
`TERMINATE` 则在预算耗尽时终止进程树并返回 `OUTPUT_LIMIT_EXCEEDED`，供产品对探索性调用执行收窄重试。
策略来自可信结构化请求，不检查 Shell 命令字符串或具体 CLI 选项。

Provider 检测到进程数超过预算且已确认收敛进程树时返回 `PROCESS_LIMIT_EXCEEDED`；只有进程树终止或
Workspace 观察无法确认时才返回 `UNKNOWN`。资源上限触发与未知副作用必须保持不同语义。

长驻会话与一次性执行共享相同的可信上下文、授权、环境解析、Sandbox Profile、输出预算、脱敏、Manifest 和审计流程。会话关闭、取消或异常退出时，Broker 先收敛底层进程与输出，再释放环境租约并完成审计记录。

## Sandbox resolution

`ImmutableSandboxProfileRegistry` 拒绝重复或内容冲突的 Profile Ref；
`ImmutableSandboxProviderRegistry` 按精确 Provider ID 解析且拒绝重复注册。`DefaultExecutionBroker`
在环境解析、Manifest 和 Provider `open` 前依次验证 Profile Ref、Provider 绑定、配置摘要、预检
Capability 与 Managed Process 支持状态。任何不匹配均 fail closed，不选择候选 Provider，也不回退
Host。

幂等重放重新执行 Capability、Workspace 和 Policy 授权；相同 idempotency key 的安全上下文、
Environment、Limits 或 Sandbox Profile 漂移会返回 `IDEMPOTENCY_CONFLICT`。

## Phase 3 shared execution Tool

`ExecutionToolProvider` records `DISPATCHED` only from the Broker's actual process-start callback. Safe preflight
failures preserve their stable provider failure code and remain `NOT_DISPATCHED`; failures after launch retain
the existing unknown-outcome protection.

The model-visible input schema exposes two mutually exclusive branches on every operating system:

- `COMMAND` sends complete shell text through the trusted host default shell and forbids `language` and `args`.
- `SCRIPT` requires one configured `language` and permits optional `args`.

The default trusted host shell is PowerShell on Windows and Bash or a POSIX shell on macOS/Linux. Script runtimes
remain an explicit host allowlist (`powershell` on Windows, `bash` on macOS/Linux, plus configured optional
runtimes). Runtime validation independently enforces the same contract before Policy or exact Approval. The
Python runs with `-X utf8` in isolated mode so piped stdout and stderr remain UTF-8 independently of the host code
page. The PowerShell SCRIPT adapter fixes stdin, stdout, and native pipeline output to UTF-8 without a BOM. On
Windows, the trusted PowerShell COMMAND shell uses a host-generated wrapper that restores the approved command from
UTF-8 Base64 and fixes console output to UTF-8 before parsing it. Bash and POSIX COMMAND behavior on macOS and Linux
is unchanged.

`ExecutionToolDefinitionFactory` 和 `ExecutionToolProvider` 把一次性命令/脚本执行作为平台级
Tool 暴露，稳定名称为 `execution.run`，产品可提供 `execution_run` 等别名。它不是 Personal
Assistant 专用实现，也没有新增 Maven 模块。

直接调用系统 `git` / `gh` 时，`SystemGitCliCommandClassifier` 生成可信的目标、风险与
`INSPECT/DIFF/MUTATE/UNKNOWN` 操作事实；Coding 产品通过自己的 `ToolPolicyRequestAdapter` 在 Policy 前解析同一
事实，Provider dispatch 时再次执行硬边界校验。复合、Wrapper、管道、重定向和逻辑运算形式整体为
`UNKNOWN`/HIGH，不会因为分类器不理解 Shell 语法而被拒绝。路径限定的假 CLI、受保护环境变量赋值、
Git Credential 配置/子命令和 GH Token 披露继续硬拒绝；为了覆盖 Wrapper 内的边界，这些检查对命令文本
采取保守匹配，疑似的受保护赋值不会降级成普通 HIGH。普通非 Git/GitHub 命令仍走既有通用 Execution 路径。
分类器只维护少量稳定类别：本地只读 LOW，本地写入或远端读取 MEDIUM，外部写入、破坏性、`gh api`、
未知和复合形式 HIGH；它不尝试实现完整 Git/GH 参数治理或 Shell Grammar。

`CommandSemanticOutcomeInterpreter` 在保留原始 `ExecutionStatus` 和 Exit Code 的同时提供稳定语义：普通退出
0 为 `SUCCEEDED`，`git diff --exit-code` / `git diff --no-index` 的退出 1 为
`EXPECTED_VARIANT/DIFFERENCES_FOUND`，`git grep` 及首个可执行文件为 `rg` / `ripgrep` 的退出 1 为
`EMPTY_RESULT/NO_MATCHES`；其他非零退出仍为
`COMMAND_FAILED`，超时、取消、输出截断终止或缺失退出码为 `OUTCOME_UNKNOWN`。该解释器不把 Build/Test
失败改写成成功，也不推断复合命令内部各 Segment 的状态。进程数超限是已确认收敛的资源失败，解释为
`COMMAND_FAILED/PROCESS_LIMIT_EXCEEDED`，不升级为未知副作用。

冻结输入只包含 `mode`、`content`、`language`、`args`、`purpose`、`timeoutMillis`；只有显式允许
Workspace 的产品配置才可以增加 `workingDirectory`。操作系统、可执行文件和 Provider 均由可信
装配解析，模型不能选择。脚本正文通过 stdin 传递，PowerShell、Bash 和 Python 由独立 runtime
adapter 按当前 OS fail closed 解析。

平台定义仍以 HIGH / NON_IDEMPOTENT 作为无法解析时的保守回退。产品可以在 Policy 前通过可信适配器提高或
降低单次调用的有效风险，但模型声明不能降低风险。审批绑定统一使用 `ToolArgumentsDigest` 的 canonical
digest，并额外冻结 execution configuration identity。相同
idempotency key 若正文、参数、环境、Profile 或配置发生漂移，将返回冲突而不是复用旧授权。
模型只收到有界、脱敏的结构化摘要；完整输出继续留在 Execution Result / Output Store 边界。

## Fixed trusted-script Tool facility

`TrustedSkillScriptToolSpec` and `TrustedSkillScriptToolProvider` let an application define a narrow business
Tool backed by one frozen Skill resource. The provider reloads and hashes host-owned script content, validates
bounded business arguments and Workspace paths, resolves an application-configured runtime, then uses the same
`ExecutionBroker`, Sandbox, Journal, cancellation, timeout, output, and asset path as ordinary execution.

The model cannot supply executable paths, source content, environment variables, arbitrary argv, endpoints, or
trust claims. This facility does not change `execution.run` approval semantics and is neutral to products and
script languages.
