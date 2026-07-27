# Scripts

后续可复用的构建、迁移和发布脚本统一放置在此目录。当前构建入口为根目录 Maven Reactor。

## Terminal UI 离线冒烟

`terminal-ui-smoke.mjs` 通过 Windows ConPTY 启动真实 shaded CLI/JLine 系统终端，记录 ANSI、
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
