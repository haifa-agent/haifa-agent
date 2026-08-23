# Scripts

后续可复用的构建、迁移、运行验证和发布脚本统一放置在此目录。当前构建入口为根目录 Maven Reactor。

## Coding Runtime 可靠性分析

`analyze-coding-runtime.ps1`（Windows）和 `analyze-coding-runtime.sh`（macOS/Linux）只读打开 Coding
Agent 的 `runtime.db`，以最新 `runtime_event.occurred_at` 为窗口终点，输出脱敏的 Run/Tool/失败分类、
Git/GH 目标、Operation Family 和恢复指标。报告 Schema 1.1 的 `requiredMetrics` 固定列出可靠性提示词
要求的全部 KPI；当前最小 SQLite Schema 能直接计算的项标为 `MEASURED/PARTIALLY_MEASURED`，缺少
Approval、Model Attempt、Context Preflight、Delivery Intent、Resume 对比或 Cost 事实源的项明确标为
`UNAVAILABLE/UNKNOWN`，不得用零值伪装为已测量。公共逻辑位于 `analyze_coding_runtime.py`。

报告不会包含 Prompt、完整命令、命令输出、Credential、Provider 原始响应、Run/Tool 原始 ID 或主机绝对
路径；Command 和 Identifier 只输出 SHA-256 摘要。输出必须位于数据目录、源码仓库和独立 docs 仓库
之外，且脚本拒绝覆盖已有报告。

```powershell
.\scripts\analyze-coding-runtime.ps1 `
  --data-root C:\Users\example\.haifa-agent\coding\data `
  --latest-hours 4 `
  --output D:\haifa-agent-test-runs\coding-runtime-report.json
```

```bash
./scripts/analyze-coding-runtime.sh \
  --data-root /srv/haifa/coding/data \
  --latest-hours 4 \
  --output /tmp/haifa-coding-runtime-report.json
```

脱敏 Replay Contract 位于
`haifa-agent-testing/haifa-agent-test-fixtures/src/main/resources/fixtures/coding-runtime-reliability/`。
分析器和 Fixture 的离线测试入口：

```bash
python3 scripts/test_analyze_coding_runtime.py
```

## Personal Assistant 真实环境

`start-real-environment.ps1`（Windows PowerShell）和 `start-real-environment.sh`
（macOS/Linux）从仓库根目录启动、复用、验证或停止 Personal Assistant 的真实联调环境。
两个入口统一调用 `real_environment.py`；`test_real_environment.py` 覆盖共享配置、参数约束和
状态文件写入。运行方法和安全边界见
[`haifa-agent-personal-assistant-server/REAL_ENVIRONMENT.md`](../haifa-agent-applications/haifa-agent-personal-assistant-server/REAL_ENVIRONMENT.md)。

后端构建完成后，脚本会按 JAR 内容摘要复制到
`local-tmp/personal-assistant-real/backend/`，并从该运行副本启动。运行中的 PA 因而不会锁定 Maven
`target/` 下的构建产物，日常 `clean`、`package` 和全仓验证无需先停止 PA。仅在后端未运行时清理旧副本；
停止流程同时兼容此前直接从 `target/` 启动的进程。
升级前已经运行的旧进程需要完成一次 `--stop` 后重新启动，才会切换到新的运行副本。
复制前会校验 Spring Boot `Main-Class`、应用 `Start-Class`、`BOOT-INF/classes` 和 `BOOT-INF/lib`；
缺失或不完整的 Maven 产物会触发一次 `package`，构建后仍不完整则明确失败且不会启动后端。

真实环境可从 `--bailian-key-file` 指定的 `KEY:VALUE` 文件可选装配阿里云百炼；region 默认
`cn-beijing`，可用 `--bailian-region` 修改。只有 API Key、Workspace ID 与 region 完整时才启用，
配置中只写 `env://DASHSCOPE_API_KEY`，启动脚本不会发起模型调用。

`--kimi-key-file` 与 `--bigmodel-key-file` 可选装配 Kimi 和智谱，默认分别读取仓库同级的
`ss-kimi.txt`、`ss-bigmodel.txt`；也可使用 `KIMI_API_KEY`、`BIGMODEL_API_KEY`。凭据只进入后端子进程
环境，配置冻结为 `env://...` 引用。可选 Provider 只扩展模型目录，不自动替换 DeepSeek 默认 Binding。

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

Windows 轻量离线冒烟与 Phase C 确定性质量入口属于测试基础设施，已迁入
[`haifa-agent-testing/scripts/`](../haifa-agent-testing/scripts/README.md)。

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
