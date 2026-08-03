# Scripts

后续可复用的构建、迁移和发布脚本统一放置在此目录。当前构建入口为根目录 Maven Reactor。

## Local Coding Agent 发行目录

`package-local-coding-agent.sh`（macOS/Linux）和 `package-local-coding-agent.ps1`（Windows）构建
`haifa-agent-cli` shaded JAR，并生成包含启动脚本、JAR 和无密钥默认配置的可搬运目录。默认输出为
用户目录下的 `.haifa-agent/coding`。公共逻辑位于 `package-local-coding-agent.py`，两个平台入口只负责
选择 Python 3 解释器和转发参数；可通过 `HAIFA_PYTHON_EXECUTABLE` 固定解释器。打包统一跳过测试，
测试应通过独立 Maven 验证命令运行：

```bash
./scripts/package-local-coding-agent.sh
```

也可直接生成到准备加入 `PATH` 的用户目录：

```bash
./scripts/package-local-coding-agent.sh "$HOME/.local/haifa-coding-agent"
export PATH="$HOME/.local/haifa-coding-agent:$PATH"
```

Windows PowerShell 使用对应脚本，发行入口为可直接加入 `PATH` 的 `haifa-coding.cmd`：

```powershell
.\scripts\package-local-coding-agent.ps1

# 也可以指定绝对路径或相对仓库根目录的路径
.\scripts\package-local-coding-agent.ps1 D:\tools\haifa-coding-agent
$env:Path = 'D:\tools\haifa-coding-agent;' + $env:Path
```

随后在任意已有项目目录执行 `haifa-coding`；启动脚本不会改变当前目录，CLI 会把它作为默认
Workspace。详细配置与安全边界见 `haifa-agent-applications/haifa-agent-cli/README.md`。

## Terminal UI 离线冒烟

`terminal-ui-smoke.mjs` 通过 Windows ConPTY 启动真实 shaded CLI 系统终端，记录 ANSI、
asciicast v2、纯文本、输入时间线、Trace 和校验清单。运行根必须位于源码仓库之外且事先不存在；
脚本只执行不会调用模型的 Terminal 命令，生成的配置使用内存存储和未使用的占位凭据。

脚本需要兼容 `node-pty` API 的本地 Node 模块。示例：

```powershell
node .\scripts\terminal-ui-smoke.mjs `
  --run-root D:\haifa-agent-test-runs\terminal-ui-smoke-001 `
  --node-pty D:\path\to\node_modules\@lydell\node-pty
```

输出目录中的 `terminal.cast` 是可重放的 Terminal 录制，`terminal.ansi` 保留原始控制序列，
`terminal.txt` 用于检索，`interaction.jsonl` 记录自动输入，`manifest.json` 给出逐项判定。

## Terminal UI 生产 CLI ConPTY 验收

`terminal-ui-conpty-acceptance.mjs` 通过真实 Windows ConPTY 启动生产 shaded CLI，覆盖完整命令、
Session Selector、Approval、`!` / `!!`、长输出、SQLite、JSONL Export、退出恢复、Workspace
Git diff 和凭据泄漏扫描。默认 `--provider deepseek` 使用外部 Provider；离线 Gate 可显式使用
`--provider stub`，脚本会启动仅绑定 loopback 的 OpenAI-compatible SSE Stub，但仍经过生产
Coding Session、Runtime、Persistence 和 Terminal 路径。

```powershell
node .\scripts\terminal-ui-conpty-acceptance.mjs `
  --run-root D:\haifa-agent-test-runs\terminal-ui-gate-b-001 `
  --node-pty D:\haifa-agent-test-tools\conpty-node\node_modules\@lydell\node-pty `
  --provider stub `
  --mode full `
  --attempt 1
```

`--mode` 支持 `full`、`approval`、`viewport` 和 `governance`。`governance` 由 Autonomous Delivery
专用 Stub Gate 调用，在 ASK 模式下同时验证一次拒绝、included/excluded Shell 批准、Windows 命令
解析和 SQLite 权威证据；它不选择 Coding Case。运行根必须位于源码仓库之外且事先不存在。
loopback HTTP 只在 CLI 进程显式设置 `HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL=true` 时允许；外部
HTTP Endpoint 即使设置该开关也会拒绝。

## tui4j Stage A Spike

`terminal-ui-tui4j-spike.mjs` 是迁移 Stage A 的历史验证入口，只启动测试 classpath 中的 tui4j
Spike，不连接 Provider、SQLite、Workspace 或执行服务。生产 CLI 已在 Stage B 切换到 tui4j；
真实产品 Gate B 使用上面的 `terminal-ui-conpty-acceptance.mjs`。Spike 仍可复现正常退出、Escape、Ctrl+C、
异常退出、Unicode 粘贴和 80x24/120x40/180x50 Resize，并保存 ANSI、asciicast、输入时间线、
屏幕快照和机器可读 manifest。

先运行 `test-compile`，再把 Coding Terminal 的 `target/test-classes`、`target/classes` 和
`dependency:build-classpath` 结果组合为绝对 classpath，传给 `--classpath`。证据目录必须位于源码
仓库之外。

## Terminal UI Phase C 跨平台质量门禁

`terminal-ui-phase-c-quality.mjs` 在当前操作系统运行 Phase C 的确定性测试集合，覆盖 Resize 状态保持、
ANSI16/ANSI256/TrueColor/NoColor、CJK/emoji/组合字符与 cell width、修饰 Enter、非 TTY fail-closed
以及 Program 生命周期。脚本有硬超时，失败返回非零，证据目录必须是源码仓库外一个尚不存在的绝对
路径：

```bash
node scripts/terminal-ui-phase-c-quality.mjs \
  --run-root /tmp/haifa-terminal-phase-c-001 \
  --timeout-seconds 600
```

Windows PowerShell 示例：

```powershell
node .\scripts\terminal-ui-phase-c-quality.mjs `
  --run-root D:\haifa-agent-test-runs\terminal-phase-c-001 `
  --timeout-seconds 600
```

输出的 `manifest.json` 将自动化结果和真实 PTY/ConPTY 结果分开。脚本不会把当前机器的单元测试冒充
其他操作系统的实机验证；macOS、Linux、Windows 与 WSL 实机项在没有对应证据时保持 `NOT_RUN`。
Windows 动态 Resize 沿用历史三次上限，保持 `SKIPPED_AFTER_3_ATTEMPTS`，不会自动发起第四次尝试。
