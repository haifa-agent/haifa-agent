# Haifa Agent End-to-End Tests

从打包后的 CLI 入口验证完整产品路径。测试通过独立 JVM 启动 shaded CLI，运行目录位于
`HAIFA_TEST_RUN_ROOT`，不访问主仓或私有配置仓的写路径。

当前补齐：

- `CP-02`～`CP-06`：九套版本化 Coding CLI Live E2E 中的关键路径 Selector，保留原 Case ID、
  独立 Oracle、脏 Workspace、失败恢复和审批拒绝零副作用断言；
- `CP-07`：真实模型发现并调用 `skill_load`，Trace 必须记录 `haifa-runtime-skill`；
- `CP-08`：真实模型依次调用 `web.search` 和 `web.fetch`；
- `CP-10`：真实 AgentRun 写入 SQLite，JSONL Transcript 投影非空；
- `CP-11`：在同一持久化装配中程序化完成模型基线、Search 拒绝、Search/Fetch 逐次批准和第二 JVM
  重开，并验证 Interaction、Tool Call、Event Journal、Outbox、Trace、Transcript 与 Secret
  不泄露。

这些都是显式 opt-in Live E2E。普通构建和 `ci-fast` 跳过；Suite Runner 会验证 Secret、预算和安全
运行根后串行执行。

Coding CLI Case 001～008 的可见验证已统一为共享 `verify_java.py` 内核与 Fixture 本地
`verify.json`，`verify.sh` 和 `verify.ps1` 只负责选择平台入口；Case 版本统一提升为 `2.0`。
Case 009 验证审批拒绝后的零副作用，不包含验证脚本。隐藏 Oracle 仍由 E2E Java 测试独立执行，
不会把 Workspace 内可修改的可见验证器当作最终判定依据。

`CP-11` 的 Approval 驱动不会预先写入答案；只有在 CLI 输出中识别到预期 Tool Target 和 `[y/N]`
提示后才写入当前响应。任何 Target 错位、缺失或额外 Approval 都会 fail closed。HTTP/SSE、Cursor
重连和跨产品 Interaction Fixture 继续由 `haifa-agent-transport-tck` 确定性验证。
