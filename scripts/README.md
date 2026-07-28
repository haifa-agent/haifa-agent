# Scripts

后续可复用的构建、迁移和发布脚本统一放置在此目录。当前构建入口为根目录 Maven Reactor。

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

`--mode` 支持 `full`、`approval` 和 `viewport`。运行根必须位于源码仓库之外且事先不存在。
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
