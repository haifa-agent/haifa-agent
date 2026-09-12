# Haifa Agent Execution Core

## Public Policy integration

`TrustedExecutionContext` 使用类型化 `ExecutionOrigin` 和可选 `sourceToolCallId` 描述可信入口。
`sourceToolCallId` 只用于关联 Runtime 已冻结的 Tool Call 事实，不是 bearer，也不能单独授权执行。
CA 与 PA 在产品装配层提供各自的 `ExecutionPolicy`；未知入口、来源与 Tool Call 形状不一致、缺少冻结
capability 时均 fail closed。Store-based `PolicyDecisionExecutionPolicy` 已退出生产装配。
Broker 自己把 `FIRST_EXECUTION`、`IDEMPOTENT_REPLAY` 或 `MANAGED_SESSION` 传给该策略；入口类型不进入
caller 可构造的 request/context。首次执行、缓存结果返回和 managed process open 之前都重新授权，
产品撤权后不会因为已有幂等结果而绕过当前策略。

`execution_run` 的用户可见审批仍发生在 Tool/Runtime 的 Interaction 层。产品 Policy 不能覆盖 Broker
既有的 Frozen Capability、Workspace、Profile、Provider、Sandbox、deadline、输出和审计硬边界。

实现 `ExecutionBroker`、内存 Journal/输出存储与 `FileChangeSet` 对账。Broker 不再以 Workspace Change
Observer 成功为执行前置条件：`execution_run` 不自动扫描 Workspace 推导文件变更，其可信事实只有授权、
Sandbox、进程 dispatch、退出状态、有界输出、超时、取消和结果未知。文件级变更证据由产品层从成功的
Mutation ToolCall、按需 Git/Plain Change Review 与 Artifact/Snapshot 引用重建；`workspace.change-set.available`
等通用 Resource 投影及其 Tool Result producer 与本 Broker 无关。

Broker 负责 capability、policy、profile、环境租约、Sandbox 生命周期、输出脱敏与审计编排，但不复制 Agent Run 状态机，也不依赖具体 Sandbox Provider。一次性执行与托管会话的展示 observer 经过有界异步分发与流式脱敏，不阻塞进程管道，observer 异常不影响进程收尾和 Execution Journal。流式脱敏（`RedactingExecutionOutputObserver`）对 URL Userinfo（`https://user:pass@host` → `https://***@host`）及环境租约注入的非基线凭据值进行跨 chunk 安全脱敏，Live 终端与 OutputStore 落盘结果保持完全一致的脱敏视图，平台基线变量（`PATH`、`USERPROFILE`、`GIT_PAGER` 等公共路径/控制值）保持保真，不破坏行号与代码事实。Provider 只在 `ProcessBuilder.start()` 成功后发出 `onStarted`，上层据此记录真实 DISPATCHED 边界。

Broker 将请求的逻辑 Scratch Spec 原样传给选定 Provider，并把一次性与 Managed Process 的创建、清理
状态带回结果。`execution_run` 的冻结配置摘要和幂等身份包含 Scratch Spec digest；Tool 结构化结果与
Runtime Event 只记录该 digest、能力和状态，不记录物理路径。

stdout/stderr 由 Provider 持续排空，并在固定内存中保留有界首部和尾部；中间省略量写入明确标记，超过
inline 阈值后返回 `AssetRef`。`ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL` 允许普通构建继续完成，
`TERMINATE` 则在预算耗尽时终止进程树并返回 `OUTPUT_LIMIT_EXCEEDED`，供产品对探索性调用执行收窄重试。
策略来自可信结构化请求，不检查 Shell 命令字符串或具体 CLI 选项。

Provider 检测到进程数超过预算且已确认收敛进程树时返回 `PROCESS_LIMIT_EXCEEDED`；只有进程树终止或结果无法确认时才返回 `UNKNOWN`。资源上限触发与未知副作用必须保持不同语义。

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
the existing unknown-outcome protection. Only explicitly typed sandbox capability preflight rejections are promoted
to that path; generic sandbox binding, configuration, or protocol failures remain fail closed.

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
Tool 暴露，稳定名称为 `execution_run`；冻结 alias 必须使用同一个值。它不是 Personal
Assistant 专用实现，也没有新增 Maven 模块。

直接调用系统 `git` / `gh` 时，`SystemGitCliCommandClassifier` 生成可信的目标、风险与
`INSPECT/DIFF/MUTATE/UNKNOWN` 操作事实；Coding 产品通过自己的 `ToolPolicyRequestAdapter` 在 Policy 前解析同一
事实，Provider dispatch 时再次执行硬边界校验。复合、Wrapper、管道、重定向和逻辑运算形式整体为
`UNKNOWN`/HIGH，不会因为分类器不理解 Shell 语法而被拒绝。路径限定的假 CLI、受保护环境变量赋值、
Git Credential 配置/子命令和 GH Token 披露继续硬拒绝；为了覆盖 Wrapper 内的边界，这些检查对命令文本
采取保守匹配，疑似的受保护赋值不会降级成普通 HIGH。普通非 Git/GitHub 命令仍走既有通用 Execution 路径。
分类器只维护少量稳定类别：本地只读 LOW，本地写入或远端读取 MEDIUM，外部写入、破坏性、`gh api`、
未知和复合形式 HIGH；它不尝试实现完整 Git/GH 参数治理或 Shell Grammar。

`CommandSemanticOutcomeInterpreter` 在保留原始 `ExecutionStatus` 和 Exit Code 的同时提供产品无关的
稳定语义：成功终态为 `SUCCEEDED`，普通非零退出统一为 `COMMAND_FAILED`，不再根据 Git、ripgrep 或
命令文本猜测正常非零变体。产品 Tool 可在调用该解释器前通过自己的冻结输入契约显式接受有文档依据的
非零退出；例如 Coding `execution_run` 使用 `expectedExitCodes`，但 Timeout、Cancel 和未知终止不能被
该契约改写为成功。该解释器不把 Build/Test 失败改写成成功，也不推断复合命令内部各 Segment 的状态。
进程数超限是已确认收敛的资源失败，解释为
`COMMAND_FAILED/PROCESS_LIMIT_EXCEEDED`，不升级为未知副作用。

冻结输入只包含 `mode`、`content`、`language`、`args`、`purpose`、`timeoutMillis`；只有显式允许
Workspace 的产品配置才可以增加 `workingDirectory`。操作系统、可执行文件和 Provider 均由可信
装配解析，模型不能选择。脚本正文通过 stdin 传递，PowerShell、Bash 和 Python 由独立 runtime adapter
执行；Host executable 与当前 OS 由 `haifa-agent-execution-host` 的 `HostScriptRuntimeResolver`
fail closed 解析，Core 仅保存显式传入的 runtime allowlist。

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
trust claims. This facility does not change `execution_run` approval semantics and is neutral to products and
script languages.
