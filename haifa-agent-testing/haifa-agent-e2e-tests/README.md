# Haifa Agent End-to-End Tests

通过最高层 `StandaloneCodingAgents` 公开装配入口和显式注入的 `CodingSessionClient` 验证完整产品
语义。运行目录位于 `HAIFA_TEST_RUN_ROOT`，不访问主仓或私有配置仓的写路径。shaded JAR、参数解析、
YAML 加载、stdio 与退出码由独立 CLI Platform Gate 验证，不属于 Critical Path Oracle。

当前补齐：

- `CP-01`：标准客户端完成真实模型基线调用；
- `CP-02`～`CP-06`：九套版本化 Coding Live E2E 中的关键路径 Selector，保留原 Case ID、
  独立 Oracle、脏 Workspace、失败恢复和审批拒绝零副作用断言；
- `CP-07`：真实模型发现并调用 `skill_load`，Trace 必须记录 `haifa-runtime-skill`；
- `CP-08`：真实模型依次调用模型可见的 `web_search` 和 `web_fetch`；
- `CP-10`：真实 AgentRun 写入 SQLite，JSONL Transcript 投影非空；
- `CP-09`：标准客户端发现并调用 Utility MCP；
- `CP-11`：在同一持久化装配中程序化完成模型基线、Search 拒绝、Search/Fetch 逐次批准和重新装配
  打开，并验证 Interaction、Tool Call、Event Journal、Outbox、Transcript 与 Secret
  不泄露。

这些都是显式 opt-in Live E2E。普通构建和 `ci-fast` 跳过；Suite Runner 会验证 Secret、预算和安全
运行根后串行执行。三端默认执行配置与产品一致，为
`host-guarded + allow + shell auto + TRUSTED_HOST_ONLY`，不再要求 Windows 专属覆盖；macOS/Linux
Local Native 严格验证由独立 Gate 负责，真实 Provider 仍不会由普通测试自动调用。

Coding CLI Case 001～008 的可见验证已统一为共享 `verify_java.py` 内核与 Fixture 本地
`verify.json`，`verify.sh` 和 `verify.ps1` 只负责选择平台入口；Case 版本统一提升为 `2.0`。
每次验证在 `.verify-out/` 下使用独立的 Run 输出目录，并随仓库外的 Case Root 保留；重复执行或
Windows 短暂文件占用不会复用或删除任何一次验证的编译目录。
Case 009 验证审批拒绝后的零副作用，不包含验证脚本。隐藏 Oracle 仍由 E2E Java 测试独立执行，
不会把 Workspace 内可修改的可见验证器当作最终判定依据。

`CP-11` 的 Approval 驱动只根据 `CodingSessionClient.pendingInteraction` 返回的权威 Target 响应；
任何 Target 错位、缺失或额外 Approval 都会 fail closed。HTTP/SSE、Cursor
重连和跨产品 Interaction Fixture 继续由 `haifa-agent-transport-tck` 确定性验证。
