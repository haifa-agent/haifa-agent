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

内置工具（含 Web / Skill）的输入 Schema 披露可选 `description` 字段：Model 在同一次 Tool Call 中生成
面向用户的简短操作意图说明；Coding Terminal 折叠卡片优先展示该说明，展开后仍保留命令/路径等安全
Target 与结果详情。说明仅用于展示，不参与授权或成功判定。

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

## Terminal Markdown

Assistant replies render heading levels, emphasis, inline code, nested lists and quotes, fenced code
panels, horizontal rules and pipe tables. Wide tables wrap cells; narrow screens show each row as
column/value lines. Rendering follows terminal cell widths, supports the four built-in themes and
keeps structure readable without color. Streaming and Resize preserve the original Markdown stored
in the Session. See the [Terminal module guide](../../haifa-agent-applications/haifa-agent-coding-terminal/README.md)
for rendering boundaries and the internal startup fallback.

## Host history bounds

Host protocol v1 Session create/open/reconcile responses defer transcript loading (`historyDeferred: true`);
the legacy `history` slot is empty. Read `session/history` separately with `sessionId`, `limit` (1–100) and an
optional exclusive `beforeSequence`. Responses contain `earlierHistoryAvailable` and `nextBeforeSequence`
(or null at the beginning). Feed that continuation into the next request to retrieve older pages.

Each history page is bounded to 512 KiB of serialized UTF-8 JSON, including JSON escaping and page metadata;
the Host retains the newest complete message-sequence groups and leaves room below its 1 MiB response cap for
the RPC envelope. Host state RPCs do not fetch transcript history; the TUI loads its bounded history page
separately. Other oversized responses retain
the request ID and return `PAYLOAD_TOO_LARGE`. Existing clients can continue requesting recent history without
a cursor; updated clients can page backwards.

## Remote workspace context

A remote TUI displays Host-authored workspace and Git facts from the optional v1 `initialize.workspaceContext`
metadata. Sidebar, footer and `/session` label the Host location; linked worktrees retain both project and
worktree branches. Shell confirmation and results identify the Host execution root, and export completion
retains the logical output path alongside its Host destination. No path or Git state is resolved on the client.

Git facts are a display snapshot taken when the client connects. Non-Git/detached workspaces and older Hosts
have explicit unavailable fallbacks. Metadata is bounded and strips terminal control characters; it grants no
filesystem access and is not used to select a command directory or authorize an operation.

## Host configuration provenance

Host startup prints the configuration source (explicit file, user `~/.haifa-agent/config.yaml`, or built-in
defaults), the ignored workspace-overlay policy, effective approval mode/threshold for new Runs, and configured tool/model
counts. The remote `/session` panel loads the same safe summary in the background through `host/configuration`;
older Hosts show an explicit unavailable fallback. Configuration contents, file paths and credentials are absent.

Host does not implicitly merge `.haifa-agent/config.yaml` from its workspace. To trust that file explicitly:

```powershell
haifa-coding host --workspace D:/projects/example --config D:/projects/example/.haifa-agent/config.yaml
```

The explicit file replaces the implicit user/workspace files, matching embedded explicit-config precedence;
normal environment/CLI overrides still apply. The selected file must supply the Host's required durable SQLite
configuration. Choosing a repository file is an explicit trust decision; default Host policy is unchanged.

## Remote context lifetime and recovery

An active remote Terminal renews its logical client context independently of sidebar polling. Checks run at
15-second intervals in the background, with at most one outstanding check. Hosts advertising `client-renewal`
provide authenticated `client/renew`; older v1 Hosts renew through a bounded Session-list read. Public
`host/ping` does not renew a context.

After expiry or Host restart, a check makes one reinitialize attempt, then the Terminal reloads and reattaches
the selected Session. The project identity must still match. Unreachable Hosts are checked again without a
busy retry loop. This recovery never automatically resends a mutation, restarts a task, grants session consent
or translates stopping observation into cancelling a Run. If recovery cannot complete, use `tui --connect`
again to explicitly reconnect.

Remote stream readers support low-core Java 21 hosts: idle event/output SSE subscriptions do not pin the
virtual-thread carriers needed by approvals and other RPCs. Stream framing accepts LF, CR and CRLF under
the existing payload bounds.

## Recoverable manual Shell commands

Manual Shell executions use the plan token as a stable execution ID. On capable Hosts, `shell/start`
returns admission without waiting for the process; `shell/get` reads a single outcome and `shell/list`
returns Session-scoped metadata without output bodies. `/shell` lists receipts, `/shell ID` recovers output,
and `/shell cancel ID` explicitly requests cancellation through the existing execution broker. Closing
a TUI, interrupting observation or losing an RPC response never cancels or resends the command.

Receipts belong to the current Host process: at most eight commands run concurrently, with 128 retained
receipts and a 30-minute retention period after completion; active commands do not expire. Evicted receipts
return unavailable and consumed tokens cannot dispatch again. Host restart is not durable Shell recovery.
Cancellation is a request until the broker reports the actual process outcome. Host shutdown requests
Shell cancellation before closing persistence. Normal asynchronous Agent Runs retain their existing lifecycle.
Old clients/Hosts keep the synchronous Shell endpoint; new clients detect optional `shell-operations`.

## Coding product SDK, Web and Terminal preview

The [browser SDK](../../haifa-agent-applications/haifa-coding-browser-client/README.md) uses the shared
product contract. [Coding Web](../../haifa-agent-applications/haifa-coding-web/README.md) and the
[independent UI example](../../examples/coding-browser-ui/README.md) consume its npm tarball and connect
directly to the same-origin Host. Node is used at build time.

The opt-in [Terminal product path](../../haifa-agent-applications/haifa-agent-coding-terminal/README.md)
uses the Java product client in remote and embedded mode. The existing native entry remains the default.
See the [Java SDK examples](../../examples/coding-product-sdk/README.md) for explicit Host ownership
and standalone Client consumption.

## Context selection and rollback

Coding defaults to `context.policy: unified-context-v1` with `context.historyRetrieval: true`.
The `targetTokens` setting is a soft target. The model's hard limit still applies.
The policy freezes per Run. Saved historical results remain readable when selection is rolled back to
legacy with readers retained. See the [CLI configuration, limits and restart/rollback procedure](../../haifa-agent-applications/haifa-agent-cli/README.md#context-selection-and-rollback).
Automatic summary maintenance after termination is unsupported.
