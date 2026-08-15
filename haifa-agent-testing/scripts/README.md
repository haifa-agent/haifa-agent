# Terminal Test Drivers

本目录保存与主仓源码、测试选择器和产品级断言绑定的 Terminal 测试驱动。私有 `test-config`
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
