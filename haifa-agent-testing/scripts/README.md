# Testing & Terminal Drivers

本目录保存与主仓源码、测试选择器和产品级断言绑定的 Terminal 测试与平台验收驱动。私有 `test-config`
只负责编排稳定 Case、Suite、环境、Secret 引用和预算，不复制这里的 Maven selector、Oracle 或
证据生成逻辑。

所有运行根必须位于主仓、`docs/` 和 `test-config/` 之外，并且在执行前不存在。生成的 ANSI、Trace、
录屏、Manifest 或其他运行证据不得提交到源码仓库。

## Windows 轻量离线冒烟

`terminal-ui-smoke.mjs` 通过 Windows ConPTY 启动生产 shaded CLI，使用内存存储且不发起模型调用，
验证启动、命令选择器、空 Session、延期能力提示、alternate screen 恢复和正常退出。脚本需要兼容
`node-pty` API 的本地 Node 模块：

```powershell
node .\haifa-agent-testing\scripts\terminal-ui-smoke.mjs `
  --run-root D:\haifa-agent-test-runs\terminal-ui-smoke-001 `
  --node-pty D:\path\to\node_modules\@lydell\node-pty
```

输出包含 ANSI、asciicast v2、纯文本、输入时间线、Trace 和逐项判定 Manifest。

## Terminal UI 生产 CLI ConPTY 验收

`terminal-ui-conpty-acceptance.mjs` 通过真实 Windows ConPTY 启动生产 shaded CLI，覆盖完整命令、
Session Selector、Approval、`!` / `!!`、长输出、SQLite、JSONL Export、退出恢复、Workspace
Git diff 和凭据泄漏扫描。默认 `--provider deepseek` 使用外部 Provider；离线 Gate 可显式使用
`--provider stub`，脚本会启动仅绑定 loopback 的 OpenAI-compatible SSE Stub，但仍经过生产
Coding Session、Runtime、Persistence 和 Terminal 路径。

```powershell
node .\haifa-agent-testing\scripts\terminal-ui-conpty-acceptance.mjs `
  --run-root D:\haifa-agent-test-runs\terminal-ui-gate-b-001 `
  --node-pty D:\haifa-agent-test-tools\conpty-node\node_modules\@lydell\node-pty `
  --provider stub `
  --mode full `
  --attempt 1
```

`--mode` 支持 `full`、`approval`、`viewport` 和 `governance`。`governance` 由 Autonomous Delivery
专用 Stub Gate 调用，在 ASK 模式下同时验证一次拒绝、included/excluded Shell 批准、Windows 命令
解析和 SQLite 权威证据；它不选择 Coding Case。运行根必须位于源码仓库之外且事先不存在。

## Terminal UI Phase C 确定性质量门禁

`terminal-ui-phase-c-quality.mjs` 在当前操作系统运行 Phase C 的确定性测试集合，覆盖 Resize 状态保持、
颜色模式、Unicode/cell width、按键路由、非 TTY fail-closed 和 Program 生命周期。脚本不会把单机
自动化结果冒充其他操作系统的真实 PTY/ConPTY 验收结果。

```bash
node haifa-agent-testing/scripts/terminal-ui-phase-c-quality.mjs \
  --run-root /tmp/haifa-terminal-phase-c-001 \
  --timeout-seconds 600
```

```powershell
node .\haifa-agent-testing\scripts\terminal-ui-phase-c-quality.mjs `
  --run-root D:\haifa-agent-test-runs\terminal-phase-c-001 `
  --timeout-seconds 600
```

输出的 `manifest.json` 将自动化结果与真实 PTY/ConPTY 结果分开；没有对应实机证据时保持 `NOT_RUN`。

## Terminal UI Unix PTY 离线冒烟

Linux/macOS 可使用 `terminal-ui-unix-pty-smoke.py` 在真实 Unix PTY 中启动本地发行包。该脚本只输入
不会触发模型的编辑器草稿与 `/quit`，验证 UTF-8、Resize、alternate-screen 进入/退出和正常进程结束；
它不会调用 Provider。`--run-root` 必须是仓库外尚不存在的目录：

```bash
python3 haifa-agent-testing/scripts/terminal-ui-unix-pty-smoke.py \
  --run-root /tmp/haifa-terminal-unix-pty-001 \
  --launcher /absolute/path/to/haifa-coding \
  --workspace /absolute/path/to/test-workspace
```

输出包含 ANSI、纯文本、asciicast v2、输入动作、结果和 SHA-256 Manifest。

## Linux 确定性发行与环境冒烟

Linux 的确定性发行/文件系统/Git/进程 Happy Path 可用 `coding-agent-linux-special-smoke.py` 验证。
它不调用模型，要求仓库外尚不存在的运行目录和已生成的 POSIX 发行启动器：

```bash
python3 haifa-agent-testing/scripts/coding-agent-linux-special-smoke.py \
  --run-root /tmp/haifa-coding-linux-special-001 \
  --launcher /absolute/path/to/haifa-coding
```

该脚本验证发行权限、空格/Unicode 路径与 cwd、Linux 大小写和可执行位、shebang、原子移动、自然
完成的子进程树，以及 Git file-mode diff、dirty diff 和 worktree 正常释放，并生成 JSON 与 SHA-256
Manifest。

## macOS Coding Terminal 人工验收启动器

`run-haifa-coding-terminal.command` 是 macOS 上的交互式人工测试启动器，用于全工具、真实 TTY、
可选本地 Utility MCP 联调和审批模式验证：

```bash
./haifa-agent-testing/scripts/run-haifa-coding-terminal.command --check
./haifa-agent-testing/scripts/run-haifa-coding-terminal.command --build
./haifa-agent-testing/scripts/run-haifa-coding-terminal.command --approval=ask
```
