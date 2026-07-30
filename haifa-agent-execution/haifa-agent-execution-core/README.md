# Haifa Agent Execution Core

## Public Policy integration

`PolicyDecisionExecutionPolicy` 要求 `TrustedExecutionContext.policyDecisionRef` 真实可查，并复核
Tenant、Principal、Run、Action、Execution 请求摘要、Snapshot，以及 `ASK` 的有效满足证据。
它不会创建 Interaction，因此 `execution.run` 的用户可见审批只发生在 Tool/Runtime 层。公共
Decision 不能覆盖 Broker 既有的 Frozen Capability、Workspace、Profile、Provider、Sandbox、
deadline、输出和审计硬边界。

实现 `ExecutionBroker`、内存 Journal/输出存储、执行前后 Workspace Manifest 及 `FileChangeSet` 对账。

Broker 负责 capability、policy、profile、环境租约、Sandbox 生命周期、输出脱敏与审计编排，但不复制 Agent Run 状态机，也不依赖具体 Sandbox Provider。一次性执行的展示 observer 经过有界异步分发，不阻塞进程管道；环境值脱敏支持 secret 跨 chunk，observer 异常不影响进程收尾和 Execution Journal。

Broker 将请求的逻辑 Scratch Spec 原样传给选定 Provider，并把一次性与 Managed Process 的创建、清理
状态带回结果。`execution.run` 的冻结配置摘要和幂等身份包含 Scratch Spec digest；Tool 结构化结果与
Runtime Event 只记录该 digest、能力和状态，不记录物理路径。

stdout/stderr 在各自执行预算内写入 `ExecutionOutputStore`，超过 inline 阈值后返回 `AssetRef`。Project Tool 只向模型返回按观察顺序合并的有界尾部，完整的分通道输出仍由 Execution Result 引用。

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

冻结输入只包含 `mode`、`content`、`language`、`args`、`purpose`、`timeoutMillis`；只有显式允许
Workspace 的产品配置才可以增加 `workingDirectory`。操作系统、可执行文件和 Provider 均由可信
装配解析，模型不能选择。脚本正文通过 stdin 传递，PowerShell、Bash 和 Python 由独立 runtime
adapter 按当前 OS fail closed 解析。

所有调用均为 HIGH / NON_IDEMPOTENT / ALWAYS approval。审批绑定统一使用
`ToolArgumentsDigest` 的 canonical digest，并额外冻结 execution configuration identity。相同
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
