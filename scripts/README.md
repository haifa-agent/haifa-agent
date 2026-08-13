# Scripts

后续可复用的构建、迁移、运行验证和发布脚本统一放置在此目录。当前构建入口为根目录 Maven Reactor。

## Personal Assistant 真实环境

`start-real-environment.ps1`（Windows PowerShell）和 `start-real-environment.sh`
（macOS/Linux）从仓库根目录启动、复用、验证或停止 Personal Assistant 的真实联调环境。
两个入口统一调用 `real_environment.py`；`test_real_environment.py` 覆盖共享配置、参数约束和
状态文件写入。运行方法和安全边界见
[`haifa-agent-personal-assistant-server/REAL_ENVIRONMENT.md`](../haifa-agent-applications/haifa-agent-personal-assistant-server/REAL_ENVIRONMENT.md)。

真实环境可从 `--bailian-key-file` 指定的 `KEY:VALUE` 文件可选装配阿里云百炼；region 默认
`cn-beijing`，可用 `--bailian-region` 修改。只有 API Key、Workspace ID 与 region 完整时才启用，
配置中只写 `env://DASHSCOPE_API_KEY`，启动脚本不会发起模型调用。

```powershell
.\scripts\start-real-environment.ps1
.\scripts\start-real-environment.ps1 --stop --dry-run
```

```bash
./scripts/start-real-environment.sh
./scripts/start-real-environment.sh --default-model-id deepseek-chat-flash
./scripts/start-real-environment.sh --stop --dry-run
```

## Haifa Coding Agent 发行目录

`package-local-coding-agent.sh`（macOS/Linux）和 `package-local-coding-agent.ps1`（Windows）构建
`haifa-agent-cli` shaded JAR，并生成包含启动脚本、JAR、无密钥默认配置以及 `data/transcripts`
目录的可搬运目录。发行配置默认启用 `SQLITE_WITH_JSONL`：SQLite 保存可恢复状态，JSONL 仅作审计
投影，payload protection 默认为适合可信本机目录的 `NONE`，无需 continuation key。默认输出为
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
Workspace。启动器根据自身位置注入 SQLite/JSONL 的绝对路径，所以可整体移动发行目录；再次打包不会
删除已有 `data/runtime.db` 或 transcript。详细配置与安全边界见
`haifa-agent-applications/haifa-agent-cli/README.md`。

Linux 的确定性发行/文件系统/Git/进程 Happy Path 可用
`coding-agent-linux-special-smoke.py` 验证。它不调用模型，要求仓库外尚不存在的运行目录和已生成的
POSIX 发行启动器：

```bash
python3 scripts/coding-agent-linux-special-smoke.py \
  --run-root /tmp/haifa-coding-linux-special-001 \
  --launcher /absolute/path/to/haifa-coding
```

该脚本验证发行权限、空格/Unicode 路径与 cwd、Linux 大小写和可执行位、shebang、原子移动、自然
完成的子进程树，以及 Git file-mode diff、dirty diff 和 worktree 正常释放，并生成 JSON 与 SHA-256
Manifest。超时、信号升级、资源耗尽和恶意路径属于单独的非 Happy Path 测试。

## Terminal UI 离线冒烟

Linux/macOS 可使用 `terminal-ui-unix-pty-smoke.py` 在真实 Unix PTY 中启动本地发行包。该脚本只输入
不会触发模型的编辑器草稿与 `/quit`，验证 UTF-8、Resize、alternate-screen 进入/退出和正常进程结束；
它不会调用 Provider。`--run-root` 必须是仓库外尚不存在的目录：

```bash
python3 scripts/terminal-ui-unix-pty-smoke.py \
  --run-root /tmp/haifa-terminal-unix-pty-001 \
  --launcher /absolute/path/to/haifa-coding \
  --workspace /absolute/path/to/test-workspace
```

输出包含 ANSI、纯文本、asciicast v2、输入动作、结果和 SHA-256 Manifest。该外部 Driver 只验证
现有生产 Terminal 在 Unix PTY 下的前台交互，不表示 Coding Agent 已提供后台 PTY Tool 或 Job 能力。

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
